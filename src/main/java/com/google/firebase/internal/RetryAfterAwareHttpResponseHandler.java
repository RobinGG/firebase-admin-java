/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpUnsuccessfulResponseHandler;
import com.google.api.client.util.Clock;
import com.google.api.client.util.Sleeper;
import com.google.common.base.Strings;
import java.io.IOException;
import java.util.Date;
import org.apache.http.client.utils.DateUtils;

final class RetryAfterAwareHttpResponseHandler implements HttpUnsuccessfulResponseHandler {

  private final HttpRetryConfig retryConfig;
  private final HttpBackOffUnsuccessfulResponseHandler backoffHandler;
  private final Clock clock;

  RetryAfterAwareHttpResponseHandler(HttpRetryConfig retryConfig) {
    this(retryConfig, Clock.SYSTEM);
  }

  RetryAfterAwareHttpResponseHandler(HttpRetryConfig retryConfig, Clock clock) {
    this.retryConfig = checkNotNull(retryConfig);
    this.backoffHandler = new HttpBackOffUnsuccessfulResponseHandler(retryConfig.newBackoff());
    this.clock = checkNotNull(clock);
  }

  void setSleeper(Sleeper sleeper) {
    backoffHandler.setSleeper(sleeper);
  }

  @Override
  public boolean handleResponse(
      HttpRequest request, HttpResponse response, boolean supportsRetry) throws IOException {

    if (!supportsRetry) {
      return false;
    }

    String retryAfter = response.getHeaders().getRetryAfter();
    if (!Strings.isNullOrEmpty(retryAfter)) {
      long delayMillis = parseRetryAfter(retryAfter.trim());
      if (delayMillis > retryConfig.getMaxIntervalMillis()) {
        return false;
      }

      if (delayMillis > 0) {
        try {
          backoffHandler.getSleeper().sleep(delayMillis);
        } catch (InterruptedException e) {
          // ignore
        }
      }
      return true;
    }

    return backoffHandler.handleResponse(request, response, true);
  }

  private long parseRetryAfter(String retryAfter) {
    try {
      return Long.parseLong(retryAfter.trim()) * 1000;
    } catch (NumberFormatException e) {
      Date date = DateUtils.parseDate(retryAfter);
      if (date != null) {
        return date.getTime() - clock.currentTimeMillis();
      }
    }
    return 0L;
  }
}
