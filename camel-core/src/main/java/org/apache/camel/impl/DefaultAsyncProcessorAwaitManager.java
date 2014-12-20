/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.processor.DefaultExchangeFormatter;
import org.apache.camel.spi.AsyncProcessorAwaitManager;
import org.apache.camel.spi.ExchangeFormatter;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.MessageHelper;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultAsyncProcessorAwaitManager extends ServiceSupport implements AsyncProcessorAwaitManager {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAsyncProcessorAwaitManager.class);

    private final Map<Exchange, AwaitThread> inflight = new ConcurrentHashMap<Exchange, AwaitThread>();
    private final ExchangeFormatter exchangeFormatter;
    private boolean interruptThreadsWhileStopping = true;

    public DefaultAsyncProcessorAwaitManager() {
        // setup exchange formatter to be used for message history dump
        DefaultExchangeFormatter formatter = new DefaultExchangeFormatter();
        formatter.setShowExchangeId(true);
        formatter.setMultiline(true);
        formatter.setShowHeaders(true);
        formatter.setStyle(DefaultExchangeFormatter.OutputStyle.Fixed);
        this.exchangeFormatter = formatter;
    }

    @Override
    public void await(Exchange exchange, CountDownLatch latch) {
        LOG.trace("Waiting for asynchronous callback before continuing for exchangeId: {} -> {}",
                exchange.getExchangeId(), exchange);
        try {
            inflight.put(exchange, new AwaitThreadEntry(Thread.currentThread(), exchange, latch));
            latch.await();
            LOG.trace("Asynchronous callback received, will continue routing exchangeId: {} -> {}",
                    exchange.getExchangeId(), exchange);

        } catch (InterruptedException e) {
            LOG.trace("Interrupted while waiting for callback, will continue routing exchangeId: {} -> {}",
                    exchange.getExchangeId(), exchange);
            exchange.setException(e);
        } finally {
            inflight.remove(exchange);
        }
    }

    @Override
    public void countDown(Exchange exchange, CountDownLatch latch) {
        LOG.trace("Asynchronous callback received for exchangeId: {}", exchange.getExchangeId());
        latch.countDown();
    }

    @Override
    public int size() {
        return inflight.size();
    }

    @Override
    public Collection<AwaitThread> browse() {
        return Collections.unmodifiableCollection(inflight.values());
    }

    @Override
    public void interrupt(String exchangeId) {
        // need to find the exchange with the given exchange id
        Exchange found = null;
        for (AsyncProcessorAwaitManager.AwaitThread entry : browse()) {
            Exchange exchange = entry.getExchange();
            if (exchangeId.equals(exchange.getExchangeId())) {
                found = exchange;
                break;
            }
        }

        if (found != null) {
            interrupt(found);
        }
    }

    @Override
    public void interrupt(Exchange exchange) {
        AwaitThreadEntry entry = (AwaitThreadEntry) inflight.get(exchange);
        if (entry != null) {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("Interrupted while waiting for asynchronous callback, will release the following blocked thread which was waiting for exchange to finish processing with exchangeId: ");
                sb.append(exchange.getExchangeId());
                sb.append("\n");

                sb.append(dumpBlockedThread(entry));

                // dump a route stack trace of the exchange
                String routeStackTrace = MessageHelper.dumpMessageHistoryStacktrace(exchange, exchangeFormatter, false);
                if (routeStackTrace != null) {
                    sb.append(routeStackTrace);
                }
                LOG.warn(sb.toString());

            } catch (Exception e) {
                throw ObjectHelper.wrapRuntimeCamelException(e);
            } finally {
                exchange.setException(new RejectedExecutionException("Interrupted while waiting for asynchronous callback for exchangeId: " + exchange.getExchangeId()));
                entry.getLatch().countDown();
            }
        }
    }

    public boolean isInterruptThreadsWhileStopping() {
        return interruptThreadsWhileStopping;
    }

    public void setInterruptThreadsWhileStopping(boolean interruptThreadsWhileStopping) {
        this.interruptThreadsWhileStopping = interruptThreadsWhileStopping;
    }

    @Override
    protected void doStart() throws Exception {
        // noop
    }

    @Override
    protected void doStop() throws Exception {
        Collection<AwaitThread> threads = browse();
        int count = threads.size();
        if (count > 0) {
            LOG.warn("Shutting down while there are still " + count + " inflight threads currently blocked.");

            StringBuilder sb = new StringBuilder();
            for (AwaitThread entry : threads) {
                sb.append(dumpBlockedThread(entry));
            }

            if (isInterruptThreadsWhileStopping()) {
                LOG.warn("The following threads are blocked and will be interrupted so the threads are released:\n" + sb.toString());
                for (AwaitThread entry : threads) {
                    try {
                        interrupt(entry.getExchange());
                    } catch (Throwable e) {
                        LOG.warn("Error while interrupting thread: " + entry.getBlockedThread().getName() + ". This exception is ignored.", e);
                    }
                }
            } else {
                LOG.warn("The following threads are blocked, and may reside in the JVM:\n" + sb.toString());
            }
        } else {
            LOG.debug("Shutting down with no inflight threads.");
        }

        inflight.clear();
    }

    private static String dumpBlockedThread(AwaitThread entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("Blocked Thread\n");
        sb.append("---------------------------------------------------------------------------------------------------------------------------------------\n");

        sb.append(style("Id:")).append(entry.getBlockedThread().getId()).append("\n");
        sb.append(style("Name:")).append(entry.getBlockedThread().getName()).append("\n");
        sb.append(style("RouteId:")).append(safeNull(entry.getRouteId())).append("\n");
        sb.append(style("NodeId:")).append(safeNull(entry.getNodeId())).append("\n");
        sb.append(style("Duration:")).append(entry.getWaitDuration()).append(" msec.\n");
        return sb.toString();
    }

    private static String style(String label) {
        return String.format("\t%-20s", label);
    }

    private static String safeNull(Object value) {
        return value != null ? value.toString() : "";
    }

    private static final class AwaitThreadEntry implements AwaitThread {
        private final Thread thread;
        private final Exchange exchange;
        private final CountDownLatch latch;
        private final long start;
        private String routeId;
        private String nodeId;

        private AwaitThreadEntry(Thread thread, Exchange exchange, CountDownLatch latch) {
            this.thread = thread;
            this.exchange = exchange;
            this.latch = latch;
            this.start = System.currentTimeMillis();

            // capture details from message history if enabled
            List<MessageHistory> list = exchange.getProperty(Exchange.MESSAGE_HISTORY, List.class);
            if (list != null && !list.isEmpty()) {
                // grab last part
                MessageHistory history = list.get(list.size() - 1);
                routeId = history.getRouteId();
                nodeId = history.getNode() != null ? history.getNode().getId() : null;
            }
        }

        @Override
        public Thread getBlockedThread() {
            return thread;
        }

        @Override
        public Exchange getExchange() {
            return exchange;
        }

        @Override
        public long getWaitDuration() {
            return System.currentTimeMillis() - start;
        }

        @Override
        public String getRouteId() {
            return routeId;
        }

        @Override
        public String getNodeId() {
            return nodeId;
        }

        public CountDownLatch getLatch() {
            return latch;
        }

        @Override
        public String toString() {
            return "AwaitThreadEntry[name=" + thread.getName() + ", exchangeId=" + exchange.getExchangeId() + "]";
        }
    }

}