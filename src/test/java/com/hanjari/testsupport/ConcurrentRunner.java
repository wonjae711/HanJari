package com.hanjari.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 동시성 테스트용 실행기. 모든 작업은 시작 barrier에 함께 도착한 뒤 동시에 진행하고,
 * 전체 작업은 유한 timeout 안에 끝나야 한다. 임의 sleep은 쓰지 않는다.
 *
 * <p>timeout·barrier 실패·작업 예외는 모두 {@link AssertionError}로 테스트를 실패시킨다.
 * 실패하면 반환하기 전에 다음 순서로 정리한다.
 * <ol>
 *   <li>{@link AbortHook}을 별도 스레드에서 유한 상한(abortTimeout) 안에 실행한다. 예: JDBC 세션 KILL·연결 abort.
 *       interrupt만으로는 JDBC 호출이 멈추지 않을 수 있기 때문이다. hook이 상한을 넘기면 그 사실을 suppressed로 남기고
 *       다음 단계로 진행한다.</li>
 *   <li>남은 작업을 interrupt한다.</li>
 *   <li>모든 작업 스레드가 끝날 때까지 유한 grace 동안 기다린다.</li>
 * </ol>
 * grace 안에 끝나지 않은 작업이 있으면 테스트 격리를 보장할 수 없다. 이 경우 그 사실을 담은 AssertionError로 실패한다.
 * 따라서 이 메서드가 반환하거나 예외를 던진 뒤에는 이 실행기가 띄운 작업이 남아 있지 않다.
 */
public final class ConcurrentRunner {

    /** 실패 후 작업 종료를 기다리는 기본 상한. */
    public static final Duration DEFAULT_TERMINATION_GRACE = Duration.ofSeconds(10);

    /** abort hook 실행 상한의 기본값. */
    public static final Duration DEFAULT_ABORT_TIMEOUT = Duration.ofSeconds(10);

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private ConcurrentRunner() {
    }

    /** 작업별 준비 단계(예: 독립 연결 확보) 후 barrier를 통과해 본 작업을 실행하는 형태. */
    @FunctionalInterface
    public interface BarrierTask<T> {
        T run(StartGate gate) throws Exception;
    }

    /** 준비가 끝난 작업이 호출해 다른 작업과 동시에 출발한다. */
    @FunctionalInterface
    public interface StartGate {
        void await() throws Exception;
    }

    /** 실패·timeout 시 interrupt 전에 호출된다. 진행 중인 외부 자원(DB 세션 등)을 강제로 끝내는 데 쓴다. */
    @FunctionalInterface
    public interface AbortHook {
        void abort() throws Exception;
    }

    /** 각 작업 시작 직전에 barrier를 기다리는 단순 형태. */
    public static <T> List<T> run(Duration timeout, List<? extends Callable<T>> tasks) {
        List<BarrierTask<T>> wrapped = new ArrayList<>();
        for (Callable<T> task : tasks) {
            wrapped.add(gate -> {
                gate.await();
                return task.call();
            });
        }
        return runWithGate(timeout, wrapped);
    }

    public static <T> List<T> runWithGate(Duration timeout, List<? extends BarrierTask<T>> tasks) {
        return runWithGate(timeout, DEFAULT_TERMINATION_GRACE, tasks, () -> { });
    }

    public static <T> List<T> runWithGate(Duration timeout, Duration terminationGrace,
                                          List<? extends BarrierTask<T>> tasks, AbortHook abortHook) {
        return runWithGate(timeout, DEFAULT_ABORT_TIMEOUT, terminationGrace, tasks, abortHook);
    }

    /**
     * @param abortTimeout abort hook 실행 상한. hook은 별도 스레드에서 실행되며, 이 시간 안에 끝나지 않아도
     *                     interrupt·종료 대기 단계는 그대로 진행한다. 따라서 실패 경로 전체는
     *                     {@code timeout + abortTimeout + terminationGrace} 안에 반환한다.
     */
    public static <T> List<T> runWithGate(Duration timeout, Duration abortTimeout, Duration terminationGrace,
                                          List<? extends BarrierTask<T>> tasks, AbortHook abortHook) {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size(), runnable -> {
            Thread thread = new Thread(runnable, "hj-concurrent-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        List<Future<T>> futures = new ArrayList<>();
        AssertionError failure = null;
        List<T> results = new ArrayList<>();
        try {
            for (BarrierTask<T> task : tasks) {
                futures.add(executor.submit(() -> task.run(() -> barrier.await(remaining(deadline), TimeUnit.NANOSECONDS))));
            }
            for (int i = 0; i < futures.size() && failure == null; i++) {
                try {
                    results.add(futures.get(i).get(remaining(deadline), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    failure = new AssertionError("Concurrent task " + i + " did not finish within " + timeout, e);
                } catch (ExecutionException e) {
                    failure = new AssertionError("Concurrent task " + i + " failed: " + e.getCause(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = new AssertionError("Interrupted while waiting for concurrent tasks", e);
                }
            }
        } catch (RuntimeException | Error e) {
            failure = new AssertionError("Concurrent runner failed: " + e, e);
        }

        if (failure == null) {
            executor.shutdown();
            return results;
        }
        if (!abortAndAwaitTermination(executor, futures, abortTimeout, terminationGrace, abortHook, failure)) {
            throw new AssertionError("Concurrent tasks still running " + terminationGrace
                    + " after abort; test isolation is not guaranteed", failure);
        }
        throw failure;
    }

    private static boolean abortAndAwaitTermination(ExecutorService executor, List<? extends Future<?>> futures,
                                                 Duration abortTimeout, Duration grace, AbortHook abortHook,
                                                 AssertionError failure) {
        // hook이 응답 없는 서버·네트워크에서 멈춰도 interrupt·종료 대기 단계에 반드시 도달하도록 상한을 둔다.
        try {
            callWithin(abortTimeout, "abort hook", () -> {
                abortHook.abort();
                return null;
            });
        } catch (Exception | Error e) {
            failure.addSuppressed(e);
        }
        futures.forEach(future -> future.cancel(true));
        executor.shutdownNow();
        boolean terminated;
        try {
            terminated = executor.awaitTermination(grace.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminated = false;
        }
        return terminated;
    }

    /**
     * {@code action}을 별도 daemon 스레드에서 실행하고 최대 {@code limit}만 기다린다.
     * 상한을 넘기면 그 스레드를 interrupt하고 {@link TimeoutException}을 던진다(결과를 기다리지 않는다).
     * action의 예외·Error는 그대로 다시 던진다.
     */
    public static <T> T callWithin(Duration limit, String what, Callable<T> action) throws Exception {
        FutureTask<T> future = new FutureTask<>(action);
        Thread thread = new Thread(future, "hj-bounded-" + THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
        try {
            return future.get(limit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            TimeoutException timeout = new TimeoutException(what + " did not finish within " + limit);
            timeout.initCause(e);
            throw timeout;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }
}
