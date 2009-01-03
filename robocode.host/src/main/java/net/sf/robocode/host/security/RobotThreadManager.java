/*******************************************************************************
 * Copyright (c) 2001, 2008 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://robocode.sourceforge.net/license/cpl-v10.html
 *
 * Contributors:
 *     Mathew A. Nelson
 *     - Initial API and implementation
 *     Flemming N. Larsen
 *     - Code cleanup
 *     - Updated to use methods from the Logger, which replaces logger methods
 *       that have been (re)moved from the robocode.util.Utils class
 *     - Moved the stopThread() method from the RobocodeDeprecated class into
 *       this class
 *     - Bugfix: The waitForStop() was using 'runThreadGroup.activeCount > 0'
 *       instead of runThread.isAlive() causing some robots to be forced to stop.
 *       In the same time this method was simplified up updated for faster CPU's   
 *     Pavel Savara
 *     - moved to RobotProxy side
 *     - forceStop is faster and smarter
 *     - start of thread is creating safe ATW queue
 *******************************************************************************/
package net.sf.robocode.host.security;


import net.sf.robocode.host.IHostedThread;
import net.sf.robocode.host.IThreadManager;
import net.sf.robocode.io.Logger;
import static net.sf.robocode.io.Logger.logError;
import static net.sf.robocode.io.Logger.logMessage;
import net.sf.robocode.security.LoggingThreadGroup;
import robocode.exception.RobotException;

import java.security.PrivilegedAction;
import java.security.AccessController;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;


/**
 * @author Mathew A. Nelson (original)
 * @author Flemming N. Larsen (contributor)
 */
public class RobotThreadManager {
	private final IHostedThread robotProxy;
	private Thread runThread;
	private LoggingThreadGroup runThreadGroup;
	private Object awtForThreadGroup;

	public RobotThreadManager(IHostedThread robotProxy) {
		this.robotProxy = robotProxy;
		createThreadGroup();
	}

	public void cleanup() {
		try {
			if (runThread == null || !runThread.isAlive()) {
				if (!discardAWT()) {
					runThreadGroup.destroy();
				}
			} else {
				Logger.logError("Warning, could not destroy " + runThread.getName());
			}
		} catch (Exception e) {
			Logger.logError("Warning, could not destroy " + runThreadGroup.getName(), e);
		}
	}

	public void initAWT() {
		if (awtForThreadGroup == null) {
			awtForThreadGroup = AccessController.doPrivileged(new PrivilegedAction<Object>() {
				public Object run() {
					return createNewAppContext();
				}
			});
		}
	}

	public boolean discardAWT() {
		boolean res = false;

		if (awtForThreadGroup != null && !(awtForThreadGroup instanceof Integer)) {
			res = disposeAppContext(awtForThreadGroup);
			awtForThreadGroup = null;
		}
		return res;
	}

	public void checkRunThread() {
		if (Thread.currentThread() != runThread) {
			throw new RobotException("You cannot take action in this thread!");
		}
	}

	public void start(IThreadManager threadManager) {
		try {
			threadManager.addThreadGroup(runThreadGroup, robotProxy);
			runThread = new Thread(runThreadGroup, robotProxy, robotProxy.getStatics().getName());
			runThread.setDaemon(true);
			runThread.setPriority(Thread.NORM_PRIORITY - 1);
			runThread.setContextClassLoader(this.robotProxy.getRobotClassloader());
			runThread.start();
		} catch (Exception e) {
			logError("Exception starting thread: ", e);
		}
	}

	/**
	 * @return true as peacefull stop
	 */
	public boolean waitForStop() {
		boolean stop = false;

		if (runThread != null && runThread.isAlive()) {
			runThread.interrupt();
			waitForStop(runThread);
			stop = runThread.isAlive();
		}

		Thread[] threads = new Thread[100];

		runThreadGroup.enumerate(threads);

		for (Thread thread : threads) {
			if (thread != null && thread != runThread && thread.isAlive()) {
				thread.interrupt();
				waitForStop(thread);
				stop |= thread.isAlive();
			}
		}

		if (stop) {
			if (!System.getProperty("NOSECURITY", "false").equals("true")) {
				logError("Robot " + robotProxy.getStatics().getName() + " is not stopping.  Forcing a stop.");
				return forceStop();
			} else {
				logError(
						"Robot " + robotProxy.getStatics().getName()
						+ " is still running.  Not stopping it because security is off.");
			}
		}

		return true;
	}

	/**
	 * @return true as peacefull stop
	 */
	public boolean forceStop() {
		int res = stopSteps(runThread);

		Thread[] threads = new Thread[100];

		runThreadGroup.enumerate(threads);

		for (Thread thread : threads) {
			if (thread != null && thread != runThread && thread.isAlive()) {
				res += stopSteps(thread);
			}
		}
		if (res > 0) {
			robotProxy.println("SYSTEM: This robot has been stopped.  No score will be generated.");

			// recycle thread group
			createThreadGroup();
		}
		runThread = null;
		return res == 0;
	}

	/**
	 * @param t thread to stop
	 * @return 0 as peacefull stop
	 */
	private int stopSteps(Thread t) {
		if (t != null && t.isAlive()) {
			interrupt(t);
			if (t.isAlive()) {
				stop(t);
			}
			if (t.isAlive()) {
				// noinspection deprecation
				// t.suspend();
				logError("Warning!  Unable to stop thread: " + runThread.getName());
			} else {
				logMessage(robotProxy.getStatics().getName() + " has been stopped.");
			}
			return 1;
		}
		return 0;
	}

	@SuppressWarnings("deprecation")
	private void stop(Thread t) {
		if (t != null) {
			// noinspection deprecation
			t.stop();
			try {
				t.join(1500);
			} catch (InterruptedException e) {
				// Immediately reasserts the exception by interrupting the caller thread itself
				Thread.currentThread().interrupt();
			}
		}
	}

	private void interrupt(Thread t) {
		if (t != null) {
			try {
				t.setPriority(Thread.MIN_PRIORITY);
			} catch (NullPointerException e) {
				logError("Sometimes this occurs in the Java core?!", e);
			}
			t.interrupt();
			try {
				t.join(500);
			} catch (InterruptedException e) {
				// Immediately reasserts the exception by interrupting the caller thread itself
				Thread.currentThread().interrupt();
			}
		}
	}

	private void waitForStop(Thread thread) {
		for (int j = 0; j < 100 && thread.isAlive(); j++) {
			if (j == 50) {
				logError(
						"Waiting for robot " + robotProxy.getStatics().getName() + " to stop thread " + thread.getName());
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				// Immediately reasserts the exception by interrupting the caller thread itself
				Thread.currentThread().interrupt();
				break; // We are in a loop
			}
		}
	}

	private void createThreadGroup() {
		runThreadGroup = new LoggingThreadGroup(robotProxy.getStatics().getName());

		// bit lower than battle have
		runThreadGroup.setMaxPriority(Thread.NORM_PRIORITY - 1);
	}

	public static Object createNewAppContext() {
		// same as SunToolkit.createNewAppContext();
		// we can't assume that we are always on Suns JVM, so we can't reference it directly
		// why we call that ? Because SunToolkit is caching AWTQueue instance form main thread group and use it on robots threads
		// and he is not asking us for checkAwtEventQueueAccess above
		try {
			final Class<?> sunToolkit = ClassLoader.getSystemClassLoader().loadClass("sun.awt.SunToolkit");
			final Method createNewAppContext = sunToolkit.getDeclaredMethod("createNewAppContext");

			return createNewAppContext.invoke(null);
		} catch (ClassNotFoundException e) {
			// we are not on sun JVM
			return -1;
		} catch (NoSuchMethodException e) {
			throw new Error("Looks like SunVM but unable to assure secured AWTQueue, sorry", e);
		} catch (InvocationTargetException e) {
			throw new Error("Looks like SunVM but unable to assure secured AWTQueue, sorry", e);
		} catch (IllegalAccessException e) {
			throw new Error("Looks like SunVM but unable to assure secured AWTQueue, sorry", e);
		}
		// end: same as SunToolkit.createNewAppContext();
	}

	public static boolean disposeAppContext(Object appContext) {
		// same as AppContext.dispose();
		try {
			final Class<?> sunToolkit = ClassLoader.getSystemClassLoader().loadClass("sun.awt.AppContext");
			final Method dispose = sunToolkit.getDeclaredMethod("dispose");

			dispose.invoke(appContext);
			return true;
		} catch (ClassNotFoundException ignore) {} catch (NoSuchMethodException ignore) {} catch (InvocationTargetException ignore) {} catch (IllegalAccessException ignore) {}
		return false;
		// end: same as AppContext.dispose();
	}

}
