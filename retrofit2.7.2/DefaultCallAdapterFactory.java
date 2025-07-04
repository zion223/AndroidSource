/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit2;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import okhttp3.Request;

final class DefaultCallAdapterFactory extends CallAdapter.Factory {
  private final @Nullable Executor callbackExecutor;

  DefaultCallAdapterFactory(@Nullable Executor callbackExecutor) {
    this.callbackExecutor = callbackExecutor;
  }

  @Override 
  public @Nullable CallAdapter<?, ?> get(
      Type returnType, Annotation[] annotations, Retrofit retrofit) {
    // 判断返回类型是否为Call类型 eg. Call<User>          
    if (getRawType(returnType) != Call.class) {
      return null;
    }
    // 判断是不是带有泛型 参数化类型
    if (!(returnType instanceof ParameterizedType)) {
      throw new IllegalArgumentException(
          "Call return type must be parameterized as Call<Foo> or Call<? extends Foo>");
    }
    final Type responseType = Utils.getParameterUpperBound(0, (ParameterizedType) returnType);

    final Executor executor = Utils.isAnnotationPresent(annotations, SkipCallbackExecutor.class)
        ? null
        : callbackExecutor;

    return new CallAdapter<Object, Call<?>>() {
      @Override 
      public Type responseType() {
        return responseType;
      }

      @Override 
      public Call<Object> adapt(Call<Object> call) {
        return executor == null
            ? call
            : new ExecutorCallbackCall<>(executor, call);
      }
    };
  }

  static final class ExecutorCallbackCall<T> implements Call<T> {
    final Executor callbackExecutor;
    final Call<T> delegate;

    // 把Call包装起来
    ExecutorCallbackCall(Executor callbackExecutor, Call<T> delegate) {
      this.callbackExecutor = callbackExecutor;
      this.delegate = delegate;
    }

    // apiService.enqueue() 调用的enqueue()方法最终调用到这里
    @Override 
    public void enqueue(final Callback<T> callback) {
      // 这里的delegate默认是OkHttpCall
      delegate.enqueue(new Callback<T>() {
        @Override public void onResponse(Call<T> call, final Response<T> response) {
          // 默认是使用MainThreadExecutor来执行 将线程切回到前台 UI线程
          // 最终的调用的是 Handler.post()方法
          callbackExecutor.execute(() -> {
            if (delegate.isCanceled()) {
              callback.onFailure(ExecutorCallbackCall.this, new IOException("Canceled"));
            } else {
              callback.onResponse(ExecutorCallbackCall.this, response);
            }
          });
        }

        @Override public void onFailure(Call<T> call, final Throwable t) {
          callbackExecutor.execute(() -> callback.onFailure(ExecutorCallbackCall.this, t));
        }
      });
    }

    @Override public boolean isExecuted() {
      return delegate.isExecuted();
    }
    // 调用的execute()方法最终调用到这里
    @Override public Response<T> execute() throws IOException {
      return delegate.execute();
    }

    @Override public void cancel() {
      delegate.cancel();
    }

    @Override public boolean isCanceled() {
      return delegate.isCanceled();
    }

    @SuppressWarnings("CloneDoesntCallSuperClone") // Performing deep clone.
    @Override public Call<T> clone() {
      return new ExecutorCallbackCall<>(callbackExecutor, delegate.clone());
    }

    @Override public Request request() {
      return delegate.request();
    }
  }
}
