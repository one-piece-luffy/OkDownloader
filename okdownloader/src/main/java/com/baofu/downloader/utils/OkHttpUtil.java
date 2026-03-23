package com.baofu.downloader.utils;


import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.baofu.downloader.rules.VideoDownloadManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.cache.CacheInterceptor;


/**
 * 基于OkHttp
 */

public class OkHttpUtil {
    public OkHttpClient mOkHttpClient;
    private static OkHttpUtil mInstance;
    public static final String URL_INVALID="Expected URL scheme 'http' or 'https' but no colon was found ";
    public static final String NO_SPACE="No space ";

    public interface METHOD{
        String GET="get";
        String POST="post";
    }


    private boolean isValidUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        // 基础前缀校验
        if (!url.startsWith("http") ) {
            return false;
        }
        // 解析URL确保格式合法
        try {
            HttpUrl.parse(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    /**
     * @param url        下载链接
     * @param method      get/post
     * @param requestCallback   回调
     */
    public void request(String url,String method, Map<String,String> header, RequestCallback requestCallback) throws IOException {
        if (isValidUrl(url)) {
            if (requestCallback != null) {
                requestCallback.onFailure(new Exception("url not start http"));
            }
            return;
        }
        // 解析URL确保格式合法
        try {
            HttpUrl.parse(url);
        } catch (Exception e) {
            return ;
        }

        if (header == null) {
            header = new ConcurrentHashMap<>();
        }

        if (!header.containsKey("User-Agent")) {
            header.put("User-Agent", VideoDownloadUtils.getUserAgent());
        }
//        if (header.containsKey("Access-Control-Request-Method")) {
//            method = header.get("Access-Control-Request-Method");
//        }
        if (METHOD.POST.equalsIgnoreCase(method)) {
            post(url, header, requestCallback);
        } else {
            get(url, header, requestCallback);
        }

    }
    public Response requestSync(String url,String method,Map<String,String> header)   {
        if (isValidUrl(url)) {
            return null;
        }
        if (header == null) {
            header = new ConcurrentHashMap<>();
        }
        if (!header.containsKey("User-Agent")) {
            header.put("User-Agent", VideoDownloadUtils.getUserAgent());
        }

        if (METHOD.POST.equalsIgnoreCase(method)) {
           return postSync(url, header);
        } else {
           return getSync(url, header);
        }
    }
    private RequestBody setRequestBody(Map<String, String> BodyParams) {
        RequestBody body ;
        okhttp3.FormBody.Builder formEncodingBuilder = new okhttp3.FormBody.Builder();
        if (BodyParams != null) {
            for (Map.Entry<String, String> entry : BodyParams.entrySet()) {
                if (entry == null)
                    continue;
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                formEncodingBuilder.add(key, value);
            }

        }
        body = formEncodingBuilder.build();
        return body;

    }
    private  Request.Builder getBuilder(Map<String,String> header) {
        Request.Builder okBuilder = new Request.Builder();
        if (header != null) {
            for (Map.Entry<String, String> entry : header.entrySet()) {
                if (entry == null)
                    continue;
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                okBuilder.addHeader(key, value);
            }

        }


        return okBuilder;
    }

    private  void post(String url, Map<String, String> header, RequestCallback requestCallback) {
        RequestBody body = setRequestBody(null);

        Request.Builder okBuilder = getBuilder(header);
        Request request = okBuilder.post(body).url(url).build();

        try {
            doAsync(request, requestCallback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void get(String url, Map<String, String> header, RequestCallback requestCallback) {
        Request.Builder okBuilder = getBuilder(header);

        Request request = okBuilder.url(url)
                .build();
        try {
            doAsync(request, requestCallback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Response postSync(String url, Map<String, String> header) {
        RequestBody body = setRequestBody(null);

        Request.Builder okBuilder = getBuilder(header);
        Request request = okBuilder.post(body).url(url).build();

        try {
            Response response= doSync(request);
            return response;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


    private  Response getSync(String url, Map<String, String> header) {
        Request.Builder okBuilder = getBuilder(header);

        Request request = okBuilder.url(url)
                .build();
        try {
            return doSync(request);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


    /**
     * 异步请求
     */
    private void doAsync(Request request,  RequestCallback requestCallback)  {
        //创建请求会话
        Call call = mOkHttpClient.newCall(request);
        //同步执行会话请求
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if(requestCallback!=null){
                    requestCallback.onFailure(e);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if(requestCallback!=null){
                    requestCallback.onResponse(response);
                }
            }
        });
    }


    /**
     * 同步请求
     */
    private Response doSync(Request request) throws IOException {

        //创建请求会话
        Call call = mOkHttpClient.newCall(request);
        //同步执行会话请求
        return call.execute();
    }

    private static class LazyHolder {
        private static final OkHttpUtil INSTANCE = new OkHttpUtil();
    }
    /**
     * @return HttpUtil实例对象
     */
    public static OkHttpUtil getInstance() {
        return LazyHolder.INSTANCE;
    }





    /**
     * 构造方法,配置OkHttpClient
     */
    public OkHttpUtil() {
        //创建okHttpClient对象
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(VideoDownloadManager.getInstance().mConfig.getConnTimeOut(), TimeUnit.SECONDS)
                .writeTimeout(VideoDownloadManager.getInstance().mConfig.getWriteTimeOut(), TimeUnit.SECONDS)
                .readTimeout(VideoDownloadManager.getInstance().mConfig.getReadTimeOut(), TimeUnit.SECONDS);
        //创建连接池，优化Connection reset出现的问题
        ConnectionPool pool = new ConnectionPool(
                10,  // 最大空闲连接数
                5,   // 空闲连接存活时间
                TimeUnit.MINUTES
        );
        builder.connectionPool(pool);

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(64);           // 最大并发请求数
        dispatcher.setMaxRequestsPerHost(10);    // 每个Host最大并发
        builder.dispatcher(dispatcher);

        builder.addInterceptor(new RedirectInterceptor());
        //使用 HTTP/2 优化Connection reset出现的问题
        builder.protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));

        builder.sslSocketFactory(SSLUtil.getInstance().getSSLSocketFactory(), SSLUtil.getInstance().getTrustManager());
        builder.hostnameVerifier(SSLUtil.getInstance().getHostnameVerifier());

        mOkHttpClient = builder.build();

    }

    public interface RequestCallback{
        void onResponse(Response response) throws IOException;
        void onFailure(Exception e);
    }

}
