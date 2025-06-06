/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.websocket;

import java.io.IOException;
import java.nio.channels.AsynchronousChannelGroup;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;

/**
 * This is a utility class that enables multiple {@link WsWebSocketContainer} instances to share a single
 * {@link AsynchronousChannelGroup} while ensuring that the group is destroyed when no longer required.
 */
public class AsyncChannelGroupUtil {

    private static final StringManager sm = StringManager.getManager(AsyncChannelGroupUtil.class);

    private static AsynchronousChannelGroup group = null;
    private static int usageCount = 0;
    private static final Object lock = new Object();


    private AsyncChannelGroupUtil() {
        // Hide the default constructor
    }


    public static AsynchronousChannelGroup register() {
        synchronized (lock) {
            if (usageCount == 0) {
                group = createAsynchronousChannelGroup();
            }
            usageCount++;
            return group;
        }
    }


    public static void unregister() {
        synchronized (lock) {
            usageCount--;
            if (usageCount == 0) {
                group.shutdown();
                group = null;
            }
        }
    }


    private static AsynchronousChannelGroup createAsynchronousChannelGroup() {
        // Need to do this with the right thread context class loader else the
        // first web app to call this will trigger a leak
        Thread currentThread = Thread.currentThread();
        ClassLoader original = currentThread.getContextClassLoader();

        try {
            currentThread.setContextClassLoader(AsyncIOThreadFactory.class.getClassLoader());

            // These are the same settings as the default
            // AsynchronousChannelGroup
            int initialSize = Runtime.getRuntime().availableProcessors();
            ExecutorService executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60,
                    TimeUnit.SECONDS, new SynchronousQueue<>(), new AsyncIOThreadFactory());

            try {
                return AsynchronousChannelGroup.withCachedThreadPool(executorService, initialSize);
            } catch (IOException e) {
                // No good reason for this to happen.
                throw new IllegalStateException(sm.getString("asyncChannelGroup.createFail"));
            }
        } finally {
            currentThread.setContextClassLoader(original);
        }
    }


    private static class AsyncIOThreadFactory implements ThreadFactory {

        private static final AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(final Runnable r) {
            Thread t = new Thread(r);
            t.setName("WebSocketClient-AsyncIO-" + count.incrementAndGet());
            t.setContextClassLoader(this.getClass().getClassLoader());
            t.setDaemon(true);
            return t;
        }
    }
}
