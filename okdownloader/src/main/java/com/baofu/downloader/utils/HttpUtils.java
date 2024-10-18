package com.baofu.downloader.utils;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.text.TextUtils;

import com.baofu.downloader.VideoDownloadManager;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class HttpUtils {

    private static final String TAG = "HttpUtils";

    public static final int MAX_RETRY_COUNT = 100;
    public static final int MAX_REDIRECT = 1;
    public static final int RESPONSE_200 = 200;
    public static final int RESPONSE_206 = 206;
    public static final int RESPONSE_403 = 403;
    public static final int RESPONSE_429 = 429;
    public static final int RESPONSE_503 = 503;
    public static final int RESPONSE_509 = 509;


}

