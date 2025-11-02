package com.minetracer.features.minetracer.database;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MineTracer Consumer Queue System
 * Based on CoreProtect's efficient batch processing design
 */
public class MineTracerConsumer implements Runnable {
    
    private static Thread consumerThread = null;
    private static volatile boolean isRunning = false;
    private static volatile boolean isPaused = false;
    private static volatile boolean shutdownRequested = false;
    
    // Dual queue system for non-blocking operation
    private static volatile int currentConsumer = 0;
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<QueueEntry>[] queues = new ConcurrentLinkedQueue[]{
        new ConcurrentLinkedQueue<>(),
        new ConcurrentLinkedQueue<>()
    };
    
    // Consumer data storage
    @SuppressWarnings("unchecked")
    private static final ConcurrentHashMap<Integer, Object>[] consumerData = new ConcurrentHashMap[]{
        new ConcurrentHashMap<>(),
        new ConcurrentHashMap<>()
    };
    
    private static final AtomicInteger entryIdCounter = new AtomicInteger(0);
    
    /**
     * Queue entry for batch processing
     */
    public static class QueueEntry {
        public final int id;
        public final int processType;
        public final Object[] data;
        public final long timestamp;
        
        public QueueEntry(int id, int processType, Object[] data) {
            this.id = id;
            this.processType = processType;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    // Process type constants
    public static final int PROCESS_CONTAINER = 0;
    public static final int PROCESS_BLOCK = 1;
    public static final int PROCESS_SIGN = 2;
    public static final int PROCESS_KILL = 3;
    public static final int PROCESS_ITEM = 4;
    public static final int PROCESS_USER = 5;
    public static final int PROCESS_WORLD = 6;
    
    /**
     * Start the consumer thread
     */
    public static void startConsumer() {
        if (!isRunning) {
            isRunning = true;
            shutdownRequested = false;
            consumerThread = new Thread(new MineTracerConsumer(), "MineTracer-Consumer");
            consumerThread.setDaemon(true);
            consumerThread.start();
            System.out.println("[MineTracer] Consumer thread started");
        }
    }
    
    /**
     * Stop the consumer thread gracefully
     */
    public static void stopConsumer() {
        if (isRunning) {
            shutdownRequested = true;
            
            // Wait for final processing
            if (consumerThread != null) {
                try {
                    consumerThread.join(5000); // Wait up to 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            isRunning = false;
            System.out.println("[MineTracer] Consumer thread stopped");
        }
    }
    
    /**
     * Add entry to queue for processing
     */
    public static void queueEntry(int processType, Object[] data, Object associatedData) {
        if (shutdownRequested) {
            return;
        }
        
        int entryId = entryIdCounter.incrementAndGet();
        QueueEntry entry = new QueueEntry(entryId, processType, data);
        
        // Add to current consumer queue
        int consumer = currentConsumer;
        queues[consumer].offer(entry);
        
        // Store associated data if provided
        if (associatedData != null) {
            consumerData[consumer].put(entryId, associatedData);
        }
    }
    
    /**
     * Main consumer loop
     */
    @Override
    public void run() {
        MineTracerProcessor processor = new MineTracerProcessor();
        
        while (isRunning || !allQueuesEmpty()) {
            try {
                // Switch consumer queue
                int processQueue = currentConsumer;
                currentConsumer = (currentConsumer + 1) % 2;
                
                // Wait for queue to populate or shutdown
                if (queues[processQueue].isEmpty() && isRunning) {
                    Thread.sleep(500);
                    continue;
                }
                
                // Process entries in batch
                List<QueueEntry> batch = new ArrayList<>();
                QueueEntry entry;
                
                // Collect batch (up to 100 entries)
                while ((entry = queues[processQueue].poll()) != null && batch.size() < 100) {
                    batch.add(entry);
                }
                
                if (!batch.isEmpty()) {
                    processor.processBatch(batch, consumerData[processQueue]);
                    
                    // Clear processed data
                    for (QueueEntry batchEntry : batch) {
                        consumerData[processQueue].remove(batchEntry.id);
                    }
                }
                
            } catch (Exception e) {
                System.err.println("[MineTracer] Consumer error: " + e.getMessage());
                e.printStackTrace();
                
                // Error delay
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // Final cleanup
        isRunning = false;
        System.out.println("[MineTracer] Consumer thread finished");
    }
    
    /**
     * Check if all queues are empty
     */
    private boolean allQueuesEmpty() {
        return queues[0].isEmpty() && queues[1].isEmpty();
    }
    
    /**
     * Get current queue size
     */
    public static int getQueueSize() {
        return queues[0].size() + queues[1].size();
    }
    
    /**
     * Check if consumer is running
     */
    public static boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Pause consumer processing
     */
    public static void pause() {
        isPaused = true;
    }
    
    /**
     * Resume consumer processing
     */
    public static void resume() {
        isPaused = false;
    }
    
    /**
     * Check if consumer is paused
     */
    public static boolean isPaused() {
        return isPaused;
    }
}