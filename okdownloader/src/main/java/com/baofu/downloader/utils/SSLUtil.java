package com.baofu.downloader.utils;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * ssl证书
 */
public class SSLUtil {
    private static SSLUtil sslUtil;
    public static SSLUtil getInstance(){
        if(sslUtil==null){
            sslUtil=new SSLUtil();
        }
        return sslUtil;
    }

    X509TrustManager myTrustManager = new X509TrustManager() {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    HostnameVerifier myHostnameVerifier = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    SSLContext sslCtx;

    SSLSocketFactory mySSLSocketFactory ;

    public X509TrustManager getTrustManager(){
        return myTrustManager;
    }
    public HostnameVerifier getHostnameVerifier(){
        return myHostnameVerifier;
    }
    public SSLSocketFactory getSSLSocketFactory(){
        if(sslCtx==null){
            try {
                sslCtx = SSLContext.getInstance("TLS");
                sslCtx.init(null, new TrustManager[] { myTrustManager }, new SecureRandom());
            } catch (KeyManagementException e) {
                e.printStackTrace();
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }
        if(mySSLSocketFactory==null){
            mySSLSocketFactory=sslCtx.getSocketFactory();
        }


        return mySSLSocketFactory;
    }

}
