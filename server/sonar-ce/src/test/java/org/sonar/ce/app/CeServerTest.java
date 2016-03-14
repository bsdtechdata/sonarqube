/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.app;

import com.google.common.base.Objects;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;
import org.sonar.ce.ComputeEngine;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class CeServerTest {
  @Rule
  public Timeout timeout = Timeout.seconds(50);
  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private CeServer underTest = null;
  private Thread waitingThread = null;

  @After
  public void tearDown() throws Exception {
    if (underTest != null) {
      underTest.stop();
    }
    Thread waitingThread = this.waitingThread;
    this.waitingThread = null;
    if (waitingThread != null) {
      waitingThread.join();
    }
  }

  @Test
  public void constructor_does_not_start_a_new_Thread() throws IOException {
    int activeCount = Thread.activeCount();

    newCeServer();

    assertThat(Thread.activeCount()).isSameAs(activeCount);
  }

  @Test
  public void start_starts_a_new_Thread() throws IOException {
    int activeCount = Thread.activeCount();

    newCeServer().start();

    assertThat(Thread.activeCount()).isSameAs(activeCount + 1);
  }

  @Test
  public void start_throws_ISE_when_called_twice() throws IOException {
    CeServer ceServer = newCeServer();

    ceServer.start();

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("start() can not be called twice");

    ceServer.start();
  }

  @Test
  public void isUp_throws_ISE_when_called_before_start() throws IOException {
    CeServer ceServer = newCeServer();

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("isUp() can not be called before start()");

    ceServer.isUp();
  }

  @Test
  public void isUp_does_not_return_true_until_ComputeEngine_startup_returns() throws InterruptedException, IOException {
    BlockingStartupComputeEngine computeEngine = new BlockingStartupComputeEngine(null);
    CeServer ceServer = newCeServer(computeEngine);

    ceServer.start();

    assertThat(ceServer.isUp()).isFalse();

    // release ComputeEngine startup method
    computeEngine.releaseStartup();

    while (!ceServer.isUp()) {
      // wait for isReady to change to true, otherwise test will fail with timeout
    }
    assertThat(ceServer.isUp()).isTrue();
  }

  @Test
  public void isUp_returns_true_when_ComputeEngine_startup_throws_any_Exception_or_Error() throws InterruptedException, IOException {
    Throwable startupException = new Throwable("Faking failing ComputeEngine#startup()");

    BlockingStartupComputeEngine computeEngine = new BlockingStartupComputeEngine(startupException);
    CeServer ceServer = newCeServer(computeEngine);

    ceServer.start();

    assertThat(ceServer.isUp()).isFalse();

    // release ComputeEngine startup method which will throw startupException
    computeEngine.releaseStartup();

    while (!ceServer.isUp()) {
      // wait for isReady to change to true, otherwise test will fail with timeout
    }
    assertThat(ceServer.isUp()).isTrue();
  }

  @Test
  public void isUp_returns_true_when_waiting_for_WebServer_failed() throws InterruptedException {
    final CountDownLatch webServerWatcherCalled = new CountDownLatch(1);
    CeServer ceServer = newCeServer(new WebServerWatcher() {
      @Override
      public boolean waitForOperational() {
        webServerWatcherCalled.countDown();
        return false;
      }
    }, DoNothingComputeEngine.INSTANCE);

    ceServer.start();
    ceServer.awaitStop();
    assertThat(ceServer.isUp()).isTrue();
  }

  @Test
  public void awaitStop_throws_ISE_if_called_before_start() throws IOException {
    CeServer ceServer = newCeServer();

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("awaitStop() must not be called before start()");

    ceServer.awaitStop();
  }

  @Test
  public void awaitStop_throws_ISE_if_called_twice() throws InterruptedException, IOException {
    final CeServer ceServer = newCeServer();
    ExceptionCatcherWaitingThread waitingThread1 = new ExceptionCatcherWaitingThread(ceServer);
    ExceptionCatcherWaitingThread waitingThread2 = new ExceptionCatcherWaitingThread(ceServer);

    ceServer.start();

    waitingThread1.start();
    waitingThread2.start();

    while (waitingThread1.isAlive() && waitingThread2.isAlive()) {
      // wait for either thread to stop because ceServer.awaitStop() failed with an exception
      // if none stops, the test will fail with timeout
    }

    Exception exception = Objects.firstNonNull(waitingThread1.getException(), waitingThread2.getException());
    assertThat(exception)
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("There can't be more than one thread waiting for the Compute Engine Server to stop");

    assertThat(waitingThread1.getException() != null && waitingThread2.getException() != null).isFalse();
  }

  @Test
  public void awaitStop_keeps_blocking_calling_thread_even_if_calling_thread_is_interrupted_but_until_stop_is_called() throws InterruptedException, IOException {
    final CeServer ceServer = newCeServer();
    Thread waitingThread = newWaitingThread(new Runnable() {
      @Override
      public void run() {
        ceServer.awaitStop();
      }
    });

    ceServer.start();
    waitingThread.start();

    // interrupts waitingThread 5 times in a row (we really insist)
    for (int i = 0; i < 5; i++) {
      waitingThread.interrupt();
      Thread.sleep(5);
      assertThat(waitingThread.isAlive()).isTrue();
    }

    ceServer.stop();
    // wait for waiting thread to stop because we stopped ceServer
    // if it does not, the test will fail with timeout
    waitingThread.join();
  }

  @Test
  public void awaitStop_unblocks_when_waiting_for_WebServer_failed() throws InterruptedException {
    final CountDownLatch webServerWatcherCalled = new CountDownLatch(1);
    CeServer ceServer = newCeServer(new WebServerWatcher() {
      @Override
      public boolean waitForOperational() {
        webServerWatcherCalled.countDown();
        return false;
      }
    }, DoNothingComputeEngine.INSTANCE);

    ceServer.start();
    // if awaitStop does not unblock, the test will fail with timeout
    ceServer.awaitStop();
  }


  @Test
  public void awaitStop_unblocks_when_waiting_for_ComputeEngine_startup_fails() throws InterruptedException, IOException {
    CeServer ceServer = newCeServer(new ComputeEngine() {
      @Override
      public void startup() {
        throw new Error("Faking ComputeEngine.startup() failing");
      }

      @Override
      public void shutdown() {
        throw new UnsupportedOperationException("shutdown() should never be called in this context");
      }
    });

    ceServer.start();
    // if awaitStop does not unblock, the test will fail with timeout
    ceServer.awaitStop();
  }

  @Test
  public void stop_releases_thread_in_awaitStop_even_when_ComputeEngine_shutdown_fails() throws InterruptedException, IOException {
    final CeServer ceServer = newCeServer(new ComputeEngine() {
      @Override
      public void startup() {
        // nothing to do at startup
      }

      @Override
      public void shutdown() {
        throw new Error("Faking ComputeEngine.shutdown() failing");
      }
    });
    Thread waitingThread = newWaitingThread(new Runnable() {
      @Override
      public void run() {
        ceServer.awaitStop();
      }
    });

    ceServer.start();
    waitingThread.start();
    ceServer.stop();
    // wait for waiting thread to stop because we stopped ceServer
    // if it does not, the test will fail with timeout
    waitingThread.join();
  }

  private CeServer newCeServer() throws IOException {
    return newCeServer(DoNothingComputeEngine.INSTANCE);
  }

  private CeServer newCeServer(ComputeEngine computeEngine) throws IOException {
    checkState(this.underTest == null, "Only one CeServer can be created per test method");
    this.underTest = new CeServer(new WebServerWatcher() {
      @Override
      public boolean waitForOperational() {
        // return instantly simulating WebServer is already operational
        return true;
      }
    }, computeEngine);
    return underTest;
  }

  private CeServer newCeServer(WebServerWatcher webServerWatcher, ComputeEngine computeEngine) {
    checkState(this.underTest == null, "Only one CeServer can be created per test method");
    this.underTest = new CeServer(webServerWatcher, computeEngine);
    return underTest;
  }

  private Thread newWaitingThread(Runnable runnable) {
    Thread t = new Thread(runnable);
    checkState(this.waitingThread == null, "Only one waiting thread can be created per test method");
    this.waitingThread = t;
    return t;
  }

  private static class BlockingStartupComputeEngine implements ComputeEngine {
    private final CountDownLatch latch = new CountDownLatch(1);
    @CheckForNull
    private final Throwable throwable;

    public BlockingStartupComputeEngine(@Nullable Throwable throwable) {
      this.throwable = throwable;
    }

    @Override
    public void startup() {
      try {
        latch.await(1000, MILLISECONDS);
      } catch (InterruptedException e) {
        throw new RuntimeException("await failed", e);
      }
      if (throwable != null) {
        if (throwable instanceof Error) {
          throw (Error) throwable;
        } else if (throwable instanceof RuntimeException) {
          throw (RuntimeException) throwable;
        }
      }
    }

    @Override
    public void shutdown() {
      // do nothing
    }

    private void releaseStartup() {
      this.latch.countDown();
    }
  }

  private static class ExceptionCatcherWaitingThread extends Thread {
    private final CeServer ceServer;
    @CheckForNull
    private Exception exception = null;

    public ExceptionCatcherWaitingThread(CeServer ceServer) {
      this.ceServer = ceServer;
    }

    @Override
    public void run() {
      try {
        ceServer.awaitStop();
      } catch (Exception e) {
        this.exception = e;
      }
    }

    @CheckForNull
    public Exception getException() {
      return exception;
    }
  }

  private enum DoNothingComputeEngine implements ComputeEngine {
    INSTANCE;

    @Override
    public void startup() {
      // do nothing
    }

    @Override
    public void shutdown() {
      // do nothing
    }
  }
}