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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import javax.annotation.Nullable;
import kotlin.coroutines.Continuation;
import okhttp3.ResponseBody;

import static retrofit2.Utils.getRawType;
import static retrofit2.Utils.methodError;

/** Adapts an invocation of an interface method into an HTTP call. */
abstract class HttpServiceMethod<ResponseT, ReturnT> extends ServiceMethod<ReturnT> {
  /**
   * Inspects the annotations on an interface method to construct a reusable service method that
   * speaks HTTP. This requires potentially-expensive reflection so it is best to build each service
   * method only once and reuse it.
   */
  static <ResponseT, ReturnT> HttpServiceMethod<ResponseT, ReturnT> parseAnnotations(
      Retrofit retrofit, Method method, RequestFactory requestFactory) {
    boolean isKotlinSuspendFunction = requestFactory.isKotlinSuspendFunction;
    boolean continuationWantsResponse = false;
    boolean continuationBodyNullable = false;

    Annotation[] annotations = method.getAnnotations();
    Type adapterType;
    if (isKotlinSuspendFunction) {
      // 被suspend关键字修饰的方法反编译后在Java中会变成在参数中以Continution为类型的参数        
      // suspend fun login(): String  => Object login(Continuation<String> var1)
      // 因此在Java中需要通过方法参数的最后一个参数的泛型来拿到真正的返回类型
      Type[] parameterTypes = method.getGenericParameterTypes();
      Type responseType = Utils.getParameterLowerBound(0,
          (ParameterizedType) parameterTypes[parameterTypes.length - 1]);
      if (getRawType(responseType) == Response.class && responseType instanceof ParameterizedType) {
        // 返回的类型是Response类型的
        responseType = Utils.getParameterUpperBound(0, (ParameterizedType) responseType);
        continuationWantsResponse = true;
      } else {
        // TODO figure out if type is nullable or not
        // Metadata metadata = method.getDeclaringClass().getAnnotation(Metadata.class)
        // Find the entry for method
        // Determine if return type is nullable or not
      }
      // 将responseType进一步封装得到adapterType
      adapterType = new Utils.ParameterizedTypeImpl(null, Call.class, responseType);
      annotations = SkipCallbackExecutorImpl.ensurePresent(annotations);
    } else {
      adapterType = method.getGenericReturnType();
    }

    /**
     * adapterType就是需要适配器去进行转换的返回值的类型 查找对应的callAdapter
     * Call<User> getUser() 对应 DefaultCallAdapterFactory
     * Observer<User> getUserRx() 对应 RxJava2CallAdapterFactory
     */
    CallAdapter<ResponseT, ReturnT> callAdapter =
        createCallAdapter(retrofit, method, adapterType, annotations);
    // responseType是返回的泛型类型 <responseType>
    Type responseType = callAdapter.responseType();
    // 如果返回的类型中泛型是Response  eg.Call<Response> 抛出异常     
    if (responseType == okhttp3.Response.class) {
      throw methodError(method, "'"
          + getRawType(responseType).getName()
          + "' is not a valid response body type. Did you mean ResponseBody?");
    }
    if (responseType == Response.class) {
      throw methodError(method, "Response must include generic type (e.g., Response<String>)");
    }
    // TODO support Unit for Kotlin?
    if (requestFactory.httpMethod.equals("HEAD") && !Void.class.equals(responseType)) {
      throw methodError(method, "HEAD method must use Void as response type.");
    }
    // 创建responseConverter
    Converter<ResponseBody, ResponseT> responseConverter =
        createResponseConverter(retrofit, method, responseType);

    // callFactory 默认是okHttpClient
    okhttp3.Call.Factory callFactory = retrofit.callFactory;
    // 判断是否是kotlin中的suspend方法
    if (!isKotlinSuspendFunction) {
        // 如果不是kotlin的suspend方法  
        /**
         * requestFactory: requestFactory
         * callFactory: okHttpClient
         * responseConverter: eg. BuiltInConverters、 GsonConverterFactory
         * callAdapter: DefaultCallAdapterFactory类的get()方法返回的callAdapter 
         *              或者自己通过addCallAdapterFactory()添加的callAdapter
         */
      return new CallAdapted<>(requestFactory, callFactory, responseConverter, callAdapter);
    } else if (continuationWantsResponse) {
      // 返回类型是Response类型的
      return (HttpServiceMethod<ResponseT, ReturnT>) new SuspendForResponse<>(requestFactory,
          callFactory, responseConverter, (CallAdapter<ResponseT, Call<ResponseT>>) callAdapter);
    } else {
      // 返回类型是实体对象
      return (HttpServiceMethod<ResponseT, ReturnT>) new SuspendForBody<>(requestFactory,
          callFactory, responseConverter, (CallAdapter<ResponseT, Call<ResponseT>>) callAdapter,
          continuationBodyNullable);
    }
  }

  private static <ResponseT, ReturnT> CallAdapter<ResponseT, ReturnT> createCallAdapter(
      Retrofit retrofit, Method method, Type returnType, Annotation[] annotations) {
    try {
      //noinspection unchecked
      return (CallAdapter<ResponseT, ReturnT>) retrofit.callAdapter(returnType, annotations);
    } catch (RuntimeException e) { // Wide exception range because factories are user code.
      throw methodError(method, e, "Unable to create call adapter for %s", returnType);
    }
  }

  private static <ResponseT> Converter<ResponseBody, ResponseT> createResponseConverter(
      Retrofit retrofit, Method method, Type responseType) {
    Annotation[] annotations = method.getAnnotations();
    try {
      return retrofit.responseBodyConverter(responseType, annotations);
    } catch (RuntimeException e) { // Wide exception range because factories are user code.
      throw methodError(method, e, "Unable to create converter for %s", responseType);
    }
  }

  private final RequestFactory requestFactory;
  private final okhttp3.Call.Factory callFactory;
  private final Converter<ResponseBody, ResponseT> responseConverter;

  // 构造方法
  HttpServiceMethod(RequestFactory requestFactory, okhttp3.Call.Factory callFactory,
      Converter<ResponseBody, ResponseT> responseConverter) {
    this.requestFactory = requestFactory;
    this.callFactory = callFactory;
    this.responseConverter = responseConverter;
  }

  /**
   *  动态代理调用的方法
   */
  @Override 
  final @Nullable ReturnT invoke(Object[] args) {
    // 创建OkHttpCall对象
    Call<ResponseT> call = new OkHttpCall<>(requestFactory, args, callFactory, responseConverter);
    // 经过adapt方法将原始的call包裹了一层 默认的目的是为了切线程（子线程执行，主线程返回）
    // 或者可以是RxJava的方法 变换 切线程等
    return adapt(call, args);
  }

  // 适配器方法 可以通过addCallAdapterFactory()方法加入适配器以支持不同的返回类型的方法
  // 比如 RxJava2CallAdapterFactory
  protected abstract @Nullable ReturnT adapt(Call<ResponseT> call, Object[] args);

  static final class CallAdapted<ResponseT, ReturnT> extends HttpServiceMethod<ResponseT, ReturnT> {
    // 成员变量callAdapter
    private final CallAdapter<ResponseT, ReturnT> callAdapter;

    CallAdapted(RequestFactory requestFactory, okhttp3.Call.Factory callFactory,
        Converter<ResponseBody, ResponseT> responseConverter,
        CallAdapter<ResponseT, ReturnT> callAdapter) {
      super(requestFactory, callFactory, responseConverter);
      this.callAdapter = callAdapter;
    }

    @Override 
    protected ReturnT adapt(Call<ResponseT> call, Object[] args) {
      // 这里是 DefaultCallAdapterFactory 中的get()方法返回的匿名内部类的adapt()方法
      // 返回的是 ExecutorCallbackCall 就是最终执行enqueue()或者execute()的类
      return callAdapter.adapt(call);
    }
  }

  // kotlin的suspend方法并且返回值是Response类型的  
  // suspend fun login(): Response<User>
  static final class SuspendForResponse<ResponseT> extends HttpServiceMethod<ResponseT, Object> {
    private final CallAdapter<ResponseT, Call<ResponseT>> callAdapter;

    SuspendForResponse(RequestFactory requestFactory, okhttp3.Call.Factory callFactory,
        Converter<ResponseBody, ResponseT> responseConverter,
        CallAdapter<ResponseT, Call<ResponseT>> callAdapter) {
      super(requestFactory, callFactory, responseConverter);
      this.callAdapter = callAdapter;
    }

    @Override 
    protected Object adapt(Call<ResponseT> call, Object[] args) {
      call = callAdapter.adapt(call);

      //noinspection unchecked Checked by reflection inside RequestFactory.
      Continuation<Response<ResponseT>> continuation =
          (Continuation<Response<ResponseT>>) args[args.length - 1];

      // See SuspendForBody for explanation about this try/catch.
      try {
        return KotlinExtensions.awaitResponse(call, continuation);
      } catch (Exception e) {
        return KotlinExtensions.suspendAndThrow(e, continuation);
      }
    }
  }

  //  suspend fun login(): User
  static final class SuspendForBody<ResponseT> extends HttpServiceMethod<ResponseT, Object> {
    private final CallAdapter<ResponseT, Call<ResponseT>> callAdapter;
    private final boolean isNullable;

    SuspendForBody(RequestFactory requestFactory, okhttp3.Call.Factory callFactory,
        Converter<ResponseBody, ResponseT> responseConverter,
        CallAdapter<ResponseT, Call<ResponseT>> callAdapter, boolean isNullable) {
      super(requestFactory, callFactory, responseConverter);
      this.callAdapter = callAdapter;
      this.isNullable = isNullable;
    }

    @Override 
    protected Object adapt(Call<ResponseT> call, Object[] args) {
      call = callAdapter.adapt(call);

      //noinspection unchecked Checked by reflection inside RequestFactory.
      Continuation<ResponseT> continuation = (Continuation<ResponseT>) args[args.length - 1];

      try {
        return isNullable
            ? KotlinExtensions.awaitNullable(call, continuation)
            : KotlinExtensions.await(call, continuation);
      } catch (Exception e) {
        return KotlinExtensions.suspendAndThrow(e, continuation);
      }
    }
  }
}
