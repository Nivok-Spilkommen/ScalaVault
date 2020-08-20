/*
 * Copyright (c) 2017-2019 m2049r et al.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ////////////////
 *
 * Copyright (c) 2020 Scala
 *
 * Please see the included LICENSE file for more information.*/

package io.scalaproject.vault.service.exchange.kraken;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import io.scalaproject.vault.service.exchange.api.ExchangeApi;
import io.scalaproject.vault.service.exchange.api.ExchangeCallback;
import io.scalaproject.vault.service.exchange.api.ExchangeException;
import io.scalaproject.vault.service.exchange.api.ExchangeRate;
import io.scalaproject.vault.util.Helper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

public class ExchangeApiImpl implements ExchangeApi {

    @NonNull
    private final OkHttpClient okHttpClient;

    private final HttpUrl baseUrl;

    //so we can inject the mockserver url
    @VisibleForTesting
    public ExchangeApiImpl(@NonNull final OkHttpClient okHttpClient, final HttpUrl baseUrl) {
        this.okHttpClient = okHttpClient;
        this.baseUrl = baseUrl;
    }

    public ExchangeApiImpl(@NonNull final OkHttpClient okHttpClient) {
        this(okHttpClient, HttpUrl.parse("https://api.kraken.com/0/public/Ticker"));
    }

    @Override
    public void queryExchangeRate(@NonNull final String baseCurrency, @NonNull final String quoteCurrency,
                                  @NonNull final ExchangeCallback callback) {

        if (baseCurrency.equals(quoteCurrency)) {
            callback.onSuccess(new ExchangeRateImpl(baseCurrency, quoteCurrency, 1.0));
            return;
        }

        boolean invertQuery;


        if (Helper.BASE_CRYPTO.equals(baseCurrency)) {
            invertQuery = false;
        } else if (Helper.BASE_CRYPTO.equals(quoteCurrency)) {
            invertQuery = true;
        } else {
            callback.onError(new IllegalArgumentException("no crypto specified"));
            return;
        }

        Timber.d("queryExchangeRate: i %b, b %s, q %s", invertQuery, baseCurrency, quoteCurrency);
        final boolean invert = invertQuery;
        final String base = invert ? quoteCurrency : baseCurrency;
        final String quote = invert ? baseCurrency : quoteCurrency;

        final HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("pair", base + (quote.equals("BTC") ? "XBT" : quote))
                .build();

        final Request httpRequest = createHttpRequest(url);

        okHttpClient.newCall(httpRequest).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(final Call call, final IOException ex) {
                callback.onError(ex);
            }

            @Override
            public void onResponse(final Call call, final Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        final JSONObject json = new JSONObject(response.body().string());
                        final JSONArray jsonError = json.getJSONArray("error");
                        if (jsonError.length() > 0) {
                            final String errorMsg = jsonError.getString(0);
                            callback.onError(new ExchangeException(response.code(), errorMsg));
                        } else {
                            final JSONObject jsonResult = json.getJSONObject("result");
                            reportSuccess(jsonResult, invert, callback);
                        }
                    } catch (JSONException ex) {
                        callback.onError(new ExchangeException(ex.getLocalizedMessage()));
                    }
                } else {
                    callback.onError(new ExchangeException(response.code(), response.message()));
                }
            }
        });
    }

    void reportSuccess(JSONObject jsonObject, boolean swapAssets, ExchangeCallback callback) {
        try {
            final ExchangeRate exchangeRate = new ExchangeRateImpl(jsonObject, swapAssets);
            callback.onSuccess(exchangeRate);
        } catch (JSONException ex) {
            callback.onError(new ExchangeException(ex.getLocalizedMessage()));
        } catch (ExchangeException ex) {
            callback.onError(ex);
        }
    }

    private Request createHttpRequest(final HttpUrl url) {
        return new Request.Builder()
                .url(url)
                .get()
                .build();
    }
}
