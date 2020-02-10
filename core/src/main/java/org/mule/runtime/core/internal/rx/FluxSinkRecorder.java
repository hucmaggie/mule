/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.rx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Utility class for using with {@link Flux#create(Consumer)}.
 *
 * @param <T> The type of values in the flux
 */
public class FluxSinkRecorder<T> implements Consumer<FluxSink<T>> {

  private volatile FluxSinkRecorderDelegate<T> delegate = new NotYetAcceptedDelegate<>();

  @Override
  public void accept(FluxSink<T> fluxSink) {
    FluxSinkRecorderDelegate<T> previousDelegate = this.delegate;
    delegate = new DirectDelegate<>(fluxSink);
    previousDelegate.accept(fluxSink);
  }

  public FluxSink<T> getFluxSink() {
    return delegate.getFluxSink();
  }

  public void next(T response) {
    delegate.next(response);
  }

  public void error(Throwable error) {
    delegate.error(error);
  }

  public void complete() {
    delegate.complete();
  }

  private interface FluxSinkRecorderDelegate<T> extends Consumer<FluxSink<T>> {

    public FluxSink<T> getFluxSink();

    public void next(T response);

    public void error(Throwable error);

    public void complete();

  }

  private static class NotYetAcceptedDelegate<T> implements FluxSinkRecorderDelegate<T> {

    private volatile FluxSink<T> fluxSink;

    // If a fluxSink as not yet been accepted, events are buffered until one is accepted
    private final List<Runnable> bufferedEvents = new ArrayList<>();

    @Override
    public void accept(FluxSink<T> fluxSink) {
      synchronized (this) {
        this.fluxSink = fluxSink;
      }
      bufferedEvents.forEach(e -> e.run());
    }

    @Override
    public synchronized FluxSink<T> getFluxSink() {
      return fluxSink;
    }

    @Override
    public void next(T response) {
      boolean present = true;
      synchronized (this) {
        if (fluxSink == null) {
          present = false;
          bufferedEvents.add(() -> fluxSink.next(response));
        }
      }

      if (present) {
        fluxSink.next(response);
      }
    }

    @Override
    public void error(Throwable error) {
      boolean present = true;
      synchronized (this) {
        if (fluxSink == null) {
          present = false;
          bufferedEvents.add(() -> fluxSink.error(error));
        }
      }

      if (present) {
        fluxSink.error(error);
      }
    }

    @Override
    public void complete() {
      boolean present = true;
      synchronized (this) {
        if (fluxSink == null) {
          present = false;
          bufferedEvents.add(() -> {
            fluxSink.complete();
          });
        }
      }

      if (present) {
        fluxSink.complete();
      }
    }
  }

  private static class DirectDelegate<T> implements FluxSinkRecorderDelegate<T> {

    private final FluxSink<T> fluxSink;

    public DirectDelegate(FluxSink<T> fluxSink) {
      this.fluxSink = fluxSink;
    }

    @Override
    public void accept(FluxSink<T> t) {
      // Nothing to do
    }

    @Override
    public FluxSink<T> getFluxSink() {
      return fluxSink;
    }

    @Override
    public void next(T response) {
      fluxSink.next(response);
    }

    @Override
    public void error(Throwable error) {
      fluxSink.error(error);
    }

    @Override
    public void complete() {
      fluxSink.complete();
    }

  }
}
