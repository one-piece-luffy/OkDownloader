package com.baofu.downloader.m3u8;

import android.text.TextUtils;

import com.baofu.downloader.rules.VideoDownloadManager;
import com.baofu.downloader.utils.HttpUtils;
import com.baofu.downloader.utils.LogUtils;
import com.baofu.downloader.utils.OkHttpUtil;
import com.baofu.downloader.utils.VideoDownloadUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

public class M3U8Utils {

    private static final String TAG = "M3U8Utils";

    /**
     * parse network M3U8 file.
     *
     * @param getChildM3u8 返回m3u8里面包含的子m3u8,true:返回子m3u8列表 false:直接解析m3u8
     */
    public static M3U8 parseNetworkM3U8Info(String videoUrl, Map<String, String> headers, int retryCount,boolean getChildM3u8,String mehtod) throws IOException {
        BufferedReader bufferedReader = null;
        Response response=null;
        try {
//            HttpURLConnection connection = HttpUtils.getConnection(videoUrl, headers, VideoDownloadUtils.getDownloadConfig().ignoreAllCertErrors);
//            int responseCode = connection.getResponseCode();

            response = OkHttpUtil.getInstance().requestSync(videoUrl,mehtod,headers);

            //todo 302处理
            if (response == null) {
                return null;
            }
            String afterUrl = response.request().url().toString();
            String requestHost=getHostUrl(videoUrl);
            String responseHost=getHostUrl(afterUrl);
            if (responseHost != null && !responseHost.equals(requestHost)) {
                //处理m3u8 302跳转导致host不一样，ts 404
                videoUrl = afterUrl;
            }
            int responseCode =response.code();
            LogUtils.i(TAG, "parseNetworkM3U8Info responseCode=" + responseCode);
            if (responseCode == HttpUtils.RESPONSE_503 && retryCount < HttpUtils.MAX_RETRY_COUNT) {
                return parseNetworkM3U8Info(videoUrl, headers, retryCount + 1,getChildM3u8,mehtod);
            }
            bufferedReader = new BufferedReader(new InputStreamReader(response.body().byteStream()));
            M3U8 m3u8 = new M3U8(videoUrl);

            float tsDuration = 0;
            int targetDuration = 0;
            int tsIndex = 0;
            int version = 0;
            int sequence = 0;
            boolean hasDiscontinuity = false;
            boolean hasEndList = false;
            boolean hasStreamInfo = false;
            boolean hasKey = false;
            boolean hasInitSegment = false;
            String method = null;
            String encryptionIV = null;
            String encryptionKeyUri = null;
            String initSegmentUri = null;
            String segmentByteRange = null;
            String line;
            String name=null;
            List<M3U8.ChildM3u8> list=null;
            int errorCount = 0;
            //从网络读取出来的key
            byte[] encryptionKey=null;
            while ((line = bufferedReader.readLine()) != null) {
                line = line.trim();
                if (TextUtils.isEmpty(line)) {
                    continue;
                }
//                LogUtils.i(TAG, "line = " + line);
                if (line.startsWith(M3U8Constants.TAG_PREFIX)) {
                    if (line.startsWith(M3U8Constants.TAG_MEDIA_DURATION)) {
                        String ret = parseStringAttr(line, M3U8Constants.REGEX_MEDIA_DURATION);
                        if (!TextUtils.isEmpty(ret)) {
                            tsDuration = Float.parseFloat(ret);
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_TARGET_DURATION)) {
                        String ret = parseStringAttr(line, M3U8Constants.REGEX_TARGET_DURATION);
                        if (!TextUtils.isEmpty(ret)) {
                            targetDuration = Integer.parseInt(ret);
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_VERSION)) {
                        String ret = parseStringAttr(line, M3U8Constants.REGEX_VERSION);
                        if (!TextUtils.isEmpty(ret)) {
                            version = Integer.parseInt(ret);
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_MEDIA_SEQUENCE)) {
                        String ret = parseStringAttr(line, M3U8Constants.REGEX_MEDIA_SEQUENCE);
                        if (!TextUtils.isEmpty(ret)) {
                            sequence = Integer.parseInt(ret);
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_STREAM_INF)) {

                        hasStreamInfo = true;
                        line = line.substring(M3U8Constants.TAG_STREAM_INF.length() + 1);
                        String[] strings = line.split(",");
                        for (String item : strings) {
                            if (item.startsWith(M3U8Constants.NAME)) {
                                try {
                                    name = item.substring(M3U8Constants.NAME.length() + 1).replace("\"","");
                                }catch (Exception e){
                                    e.printStackTrace();
                                }
                                break;
                            }
                        }
                        if (TextUtils.isEmpty(name)) {
                            for (String item : strings) {

                                if (item.startsWith(M3U8Constants.RESOLUTION)) {
                                    item = item.substring(M3U8Constants.RESOLUTION.length() + 1);
                                    try {
                                        String[] arr = item.split("x");
                                        if (arr.length > 1) {
                                            name = arr[1].replace("\"","");
                                        } else {
                                            name = arr[0].replace("\"","");
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    break;
                                }
                            }
                        }

                    } else if (line.startsWith(M3U8Constants.TAG_DISCONTINUITY)) {
                        hasDiscontinuity = true;
                    } else if (line.startsWith(M3U8Constants.TAG_ENDLIST)) {
                        hasEndList = true;
                    } else if (line.startsWith(M3U8Constants.TAG_KEY)) {
                        hasKey = true;
                        method = parseOptionalStringAttr(line, M3U8Constants.REGEX_METHOD);
                        String keyFormat = parseOptionalStringAttr(line, M3U8Constants.REGEX_KEYFORMAT);
                        if (!M3U8Constants.METHOD_NONE.equals(method)) {
                            encryptionIV = parseOptionalStringAttr(line, M3U8Constants.REGEX_IV);
                            if (M3U8Constants.KEYFORMAT_IDENTITY.equals(keyFormat) || keyFormat == null) {
                                if (M3U8Constants.METHOD_AES_128.equals(method)) {
                                    // The segment is fully encrypted using an identity key.
                                    String tempKeyUri = parseStringAttr(line, M3U8Constants.REGEX_URI);
                                    if (tempKeyUri != null) {
                                        encryptionKeyUri = getM3U8AbsoluteUrl(videoUrl, tempKeyUri);
                                    }
                                } else {
                                    // Do nothing. Samples are encrypted using an identity key,
                                    // but this is not supported. Hopefully, a traditional DRM
                                    // alternative is also provided.
                                }
                            } else {
                                // Do nothing.
                            }
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_INIT_SEGMENT)) {
                        String tempInitSegmentUri = parseStringAttr(line, M3U8Constants.REGEX_URI);
                        if (!TextUtils.isEmpty(tempInitSegmentUri)) {
                            hasInitSegment = true;
                            initSegmentUri = getM3U8AbsoluteUrl(videoUrl, tempInitSegmentUri);
                            segmentByteRange = parseOptionalStringAttr(line, M3U8Constants.REGEX_ATTR_BYTERANGE);
                        }
                    }
                    continue;
                }
                // It has '#EXT-X-STREAM-INF' tag;
                if (hasStreamInfo) {
                    if (list == null) {
                        list = new ArrayList<>();
                    }
                    M3U8.ChildM3u8 childM3u8 = new M3U8.ChildM3u8();
                    childM3u8.name = name;
                    childM3u8.url = getM3U8AbsoluteUrl(videoUrl, line);
                    list.add(childM3u8);
                    name=null;
                }
                if (Math.abs(tsDuration) < 0.001f) {
                    continue;
                }
                M3U8Seg ts = new M3U8Seg();
                String tsUrl = getM3U8AbsoluteUrl(videoUrl, line);


                ts.initTsAttributes(tsUrl, tsDuration, tsIndex, sequence++, hasDiscontinuity);
                if (hasKey) {
                    if (encryptionKey == null&&VideoDownloadManager.getInstance().mConfig.decryptM3u8) {
                        encryptionKey = parseKey(encryptionKeyUri,method);
                        ts.encryptionKey = encryptionKey;
                    }
                    ts.encryptionKey = encryptionKey;
                    ts.setKeyConfig(method, encryptionKeyUri, encryptionIV);
                    m3u8.encryptionKey=encryptionKey;
                    m3u8.encryptionIV=encryptionIV;
                }
                if (hasInitSegment) {
                    ts.setInitSegmentInfo(initSegmentUri, segmentByteRange);
                }
                m3u8.addTs(ts);
                tsIndex++;
                tsDuration = 0;
                hasStreamInfo = false;
                hasDiscontinuity = false;
                hasKey = false;
                hasInitSegment = false;
                method = null;
                encryptionKeyUri = null;
                encryptionIV = null;
                initSegmentUri = null;
                segmentByteRange = null;
                name = null;
            }

            m3u8.setTargetDuration(targetDuration);
            m3u8.setVersion(version);
            m3u8.setSequence(sequence);
            m3u8.setHasEndList(hasEndList);
            m3u8.childM3u8s = list;

            if (getChildM3u8 && list != null && list.size() == 1 && list.get(0) != null) {
                //子m3u8只有一个的那就直接解析
                String url=getM3U8AbsoluteUrl(videoUrl, list.get(0).url);
                return parseNetworkM3U8Info(url, headers, retryCount, getChildM3u8, mehtod);
            }
            if (!getChildM3u8 && list != null && list.size() > 0 && list.get(0) != null) {
                String url=getM3U8AbsoluteUrl(videoUrl, list.get(0).url);
                return parseNetworkM3U8Info(url, headers, retryCount, getChildM3u8,mehtod);
            }
            m3u8.filename=getFileName(response,videoUrl);
            m3u8.header=headers;
            return m3u8;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {

            VideoDownloadUtils.close(bufferedReader);
            if(response!=null){
                VideoDownloadUtils.close(response.body());
            }
        }
        return null;
    }

    public static M3U8 parseLocalM3U8File(File m3u8File) throws IOException {
        InputStreamReader inputStreamReader = null;
        BufferedReader bufferedReader = null;
        try {
            inputStreamReader = new InputStreamReader(new FileInputStream(m3u8File));
            bufferedReader = new BufferedReader(inputStreamReader);
            M3U8 m3u8 = new M3U8();
            float tsDuration = 0;
            int targetDuration = 0;
            int tsIndex = 0;
            int version = 0;
            int sequence = 0;
            boolean hasDiscontinuity = false;
            boolean hasKey = false;
            boolean hasInitSegment = false;
            String method = null;
            String encryptionIV = null;
            String encryptionKeyUri = null;
            String initSegmentUri = null;
            String segmentByteRange = null;
            String line;
            //从网络读取出来的key
            byte[] encryptionKey=null;
            while ((line = bufferedReader.readLine()) != null) {
                LogUtils.i(TAG, "line = " + line);
                if (line.startsWith(M3U8Constants.TAG_PREFIX)) {
                    if (line.startsWith(M3U8Constants.TAG_MEDIA_DURATION)) {
                        String ret = parseStringAttr(line, M3U8Constants.REGEX_MEDIA_DURATION);
                        if (!TextUtils.isEmpty(ret)) {
                            tsDuration = Float.parseFloat(ret);
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_TARGET_DURATION)) {
                        String ret = parseStringAttr(line, M3U8Constants.REGEX_TARGET_DURATION);
                        if (!TextUtils.isEmpty(ret)) {
                            targetDuration = Integer.parseInt(ret);
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_VERSION)) {
                        String ret = parseStringAttr(line, M3U8Constants.REGEX_VERSION);
                        if (!TextUtils.isEmpty(ret)) {
                            version = Integer.parseInt(ret);
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_MEDIA_SEQUENCE)) {
                        String ret = parseStringAttr(line, M3U8Constants.REGEX_MEDIA_SEQUENCE);
                        if (!TextUtils.isEmpty(ret)) {
                            sequence = Integer.parseInt(ret);
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_DISCONTINUITY)) {
                        hasDiscontinuity = true;
                    } else if (line.startsWith(M3U8Constants.TAG_KEY)) {
                        hasKey = true;
                        method = parseOptionalStringAttr(line, M3U8Constants.REGEX_METHOD);
                        String keyFormat = parseOptionalStringAttr(line, M3U8Constants.REGEX_KEYFORMAT);
                        if (!M3U8Constants.METHOD_NONE.equals(method)) {
                            encryptionIV = parseOptionalStringAttr(line, M3U8Constants.REGEX_IV);
                            if (M3U8Constants.KEYFORMAT_IDENTITY.equals(keyFormat) || keyFormat == null) {
                                if (M3U8Constants.METHOD_AES_128.equals(method)) {
                                    // The segment is fully encrypted using an identity key.
                                    encryptionKeyUri = parseStringAttr(line, M3U8Constants.REGEX_URI);
                                } else {
                                    // Do nothing. Samples are encrypted using an identity key,
                                    // but this is not supported. Hopefully, a traditional DRM
                                    // alternative is also provided.
                                }
                            } else {
                                // Do nothing.
                            }
                        }
                    } else if (line.startsWith(M3U8Constants.TAG_INIT_SEGMENT)) {
                        initSegmentUri = parseStringAttr(line, M3U8Constants.REGEX_URI);
                        if (!TextUtils.isEmpty(initSegmentUri)) {
                            hasInitSegment = true;
                            segmentByteRange = parseOptionalStringAttr(line, M3U8Constants.REGEX_ATTR_BYTERANGE);
                        }
                    }
                    continue;
                }
                M3U8Seg ts = new M3U8Seg();
                ts.initTsAttributes(line, tsDuration, tsIndex, sequence++, hasDiscontinuity);
                if (hasKey) {
                    if (encryptionKey == null&& VideoDownloadManager.getInstance().mConfig.decryptM3u8) {
                        encryptionKey = parseKey(encryptionKeyUri,method);
                        ts.encryptionKey = encryptionKey;
                    }
                    ts.encryptionKey = encryptionKey;
                    ts.setKeyConfig(method, encryptionKeyUri, encryptionIV);
                    m3u8.encryptionKey=encryptionKey;
                    m3u8.encryptionIV=encryptionIV;
                }
                if (hasInitSegment) {
                    ts.setInitSegmentInfo(initSegmentUri, segmentByteRange);
                }
                m3u8.addTs(ts);
                tsIndex++;
                tsDuration = 0;
                hasDiscontinuity = false;
                hasKey = false;
                hasInitSegment = false;
                method = null;
                encryptionKeyUri = null;
                encryptionIV = null;
                initSegmentUri = null;
                segmentByteRange = null;
            }
            m3u8.setTargetDuration(targetDuration);
            m3u8.setVersion(version);
            m3u8.setSequence(sequence);
            return m3u8;
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        } finally {
            VideoDownloadUtils.close(inputStreamReader);
            VideoDownloadUtils.close(bufferedReader);
        }
    }

    public static String parseStringAttr(String line, Pattern pattern) {
        if (pattern == null)
            return null;
        Matcher matcher = pattern.matcher(line);
        if (matcher.find() && matcher.groupCount() == 1) {
            return matcher.group(1);
        }
        return null;
    }

    public static String parseOptionalStringAttr(String line, Pattern pattern) {
        if (pattern == null)
            return null;
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static void createRemoteM3U8(File dir, M3U8 m3u8) throws IOException {
        File m3u8File = new File(dir, VideoDownloadUtils.REMOTE_M3U8);
        if (m3u8File.exists()) {
            m3u8File.delete();
            return;
        }
        BufferedWriter bfw = new BufferedWriter(new FileWriter(m3u8File, false));
        bfw.write(M3U8Constants.PLAYLIST_HEADER + "\n");
        bfw.write(M3U8Constants.TAG_VERSION + ":" + m3u8.getVersion() + "\n");
        bfw.write(M3U8Constants.TAG_MEDIA_SEQUENCE + ":" + m3u8.getInitSequence() + "\n");
        bfw.write(M3U8Constants.TAG_TARGET_DURATION + ":" + m3u8.getTargetDuration() + "\n");
        for (M3U8Seg m3u8Ts : m3u8.getTsList()) {
            if (m3u8Ts.hasInitSegment()) {
                String initSegmentInfo;
                if (m3u8Ts.getSegmentByteRange() != null) {
                    initSegmentInfo = "URI=\"" + m3u8Ts.getInitSegmentUri() + "\"" + ",BYTERANGE=\"" + m3u8Ts.getSegmentByteRange() + "\"";
                } else {
                    initSegmentInfo = "URI=\"" + m3u8Ts.getInitSegmentUri() + "\"";
                }
                bfw.write(M3U8Constants.TAG_INIT_SEGMENT + ":" + initSegmentInfo + "\n");
            }
            if (m3u8Ts.hasKey()) {
                if (m3u8Ts.getMethod() != null) {
                    String key = "METHOD=" + m3u8Ts.getMethod();
                    if (m3u8Ts.getKeyUri() != null) {
                        String keyUri = m3u8Ts.getKeyUri();
                        key += ",URI=\"" + keyUri + "\"";
                        URL keyURL = new URL(keyUri);
                        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(keyURL.openStream()));
                        StringBuilder textBuilder = new StringBuilder();
                        String line;
                        while ((line = bufferedReader.readLine()) != null) {
                            textBuilder.append(line);
                        }
                        boolean isMessyStr = VideoDownloadUtils.isMessyCode(textBuilder.toString());
                        m3u8Ts.setIsMessyKey(isMessyStr);
                        File keyFile = new File(dir, m3u8Ts.getLocalKeyUri());
                        FileOutputStream outputStream = new FileOutputStream(keyFile);
                        outputStream.write(textBuilder.toString().getBytes());
                        bufferedReader.close();
                        outputStream.close();
                        if (m3u8Ts.getKeyIV() != null) {
                            key += ",IV=" + m3u8Ts.getKeyIV();
                        }
                    }
                    bfw.write(M3U8Constants.TAG_KEY + ":" + key + "\n");
                }
            }
            if (m3u8Ts.hasDiscontinuity()) {
                bfw.write(M3U8Constants.TAG_DISCONTINUITY + "\n");
            }
            bfw.write(M3U8Constants.TAG_MEDIA_DURATION + ":" + m3u8Ts.getDuration() + ",\n");
            bfw.write(m3u8Ts.getUrl());
            bfw.newLine();
        }
        bfw.write(M3U8Constants.TAG_ENDLIST);
        bfw.flush();
        bfw.close();
    }

    public static String getM3U8AbsoluteUrl(String videoUrl, String line) {
        if (TextUtils.isEmpty(videoUrl) || TextUtils.isEmpty(line)) {
            return "";
        }
        if (videoUrl.startsWith("file://") || videoUrl.startsWith("/")) {
            return videoUrl;
        }
        String baseUriPath = getBaseUrl(videoUrl);
        String hostUrl = getHostUrl(videoUrl);
        if (line.startsWith("//")) {
            String tempUrl = getSchema(videoUrl) + ":" + line;
            return tempUrl.trim();
        }
        if (line.startsWith("/")) {
            String pathStr = getPathStr(videoUrl);
            String longestCommonPrefixStr = getLongestCommonPrefixStr(pathStr, line);
            if (hostUrl.endsWith("/")) {
                hostUrl = hostUrl.substring(0, hostUrl.length() - 1);
            }
            String tempUrl = hostUrl + longestCommonPrefixStr + line.substring(longestCommonPrefixStr.length());
            return tempUrl.trim();
        }
        String result=null;
        if (line.startsWith("http")) {
            result= line;
        }else {
            result= baseUriPath + line;
        }
        return result.trim();
    }

    private static String getSchema(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        int index = url.indexOf("://");
        if (index != -1) {
            String result = url.substring(0, index);
            return result;
        }
        return "";
    }

    /**
     * 例如https://xvideo.d666111.com/xvideo/taohuadao56152307/index.m3u8
     * 我们希望得到https://xvideo.d666111.com/xvideo/taohuadao56152307/
     *
     * @param url
     * @return
     */
    public static String getBaseUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        int slashIndex = url.lastIndexOf("/");
        if (slashIndex != -1) {
            return url.substring(0, slashIndex + 1);
        }
        return url;
    }

    /**
     * 例如https://xvideo.d666111.com/xvideo/taohuadao56152307/index.m3u8
     * 我们希望得到https://xvideo.d666111.com/
     *
     * @param url
     * @return
     */
    public static String getHostUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        try {
            URL formatURL = new URL(url);
            String host = formatURL.getHost();
            if (host == null) {
                return url;
            }
            int hostIndex = url.indexOf(host);
            if (hostIndex != -1) {
                int port = formatURL.getPort();
                String resultUrl;
                if (port != -1) {
                    resultUrl = url.substring(0, hostIndex + host.length()) + ":" + port + "/";
                } else {
                    resultUrl = url.substring(0, hostIndex + host.length()) + "/";
                }
                return resultUrl;
            }
            return url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 例如https://xvideo.d666111.com/xvideo/taohuadao56152307/index.m3u8
     * 我们希望得到   /xvideo/taohuadao56152307/index.m3u8
     *
     * @param url
     * @return
     */
    public static String getPathStr(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String hostUrl = getHostUrl(url);
        if (TextUtils.isEmpty(hostUrl)) {
            return url;
        }
        return url.substring(hostUrl.length() - 1);
    }

    /**
     * 获取两个字符串的最长公共前缀
     * /xvideo/taohuadao56152307/500kb/hls/index.m3u8   与     /xvideo/taohuadao56152307/index.m3u8
     * <p>
     * /xvideo/taohuadao56152307/500kb/hls/jNd4fapZ.ts  与     /xvideo/taohuadao56152307/500kb/hls/index.m3u8
     *
     * @param str1
     * @param str2
     * @return
     */
    public static String getLongestCommonPrefixStr(String str1, String str2) {
        if (TextUtils.isEmpty(str1) || TextUtils.isEmpty(str2)) {
            return "";
        }
        if (TextUtils.equals(str1, str2)) {
            return str1;
        }
        char[] arr1 = str1.toCharArray();
        char[] arr2 = str2.toCharArray();
        int j = 0;
        while (j < arr1.length && j < arr2.length) {
            if (arr1[j] != arr2[j]) {
                break;
            }
            j++;
        }
        return str1.substring(0, j);
    }
    public static byte[] parseKey(String url,String method){
        if (TextUtils.isEmpty(url))
            return null;
        url = url.trim();
        if (!url.startsWith("http")) {
            return null;
        }
        try {
            Response response= OkHttpUtil.getInstance().requestSync(url,method,null);
            InputStream inStream = response.body().byteStream();
            //key只能是16位
            byte[] buffer = new byte[16];
            if ((inStream.read(buffer)) != -1) {
                return buffer;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getFileName(Response response,String url) {
        String name = null;
        String charset = "UTF-8";
        if(response!=null){
            try {

                String uriPath = response.request().url().uri().getRawPath();
                name = uriPath.substring(uriPath.lastIndexOf("/") + 1);

                String contentDisposition = response.header("Content-Disposition");
                if (contentDisposition != null) {
                    int p1 = contentDisposition.indexOf("filename");
                    //有的Content-Disposition里面的filename后面是*=，是*=的文件名后面一般都带了编码名称，按它提供的编码进行解码可以避免文件名乱码
                    int p2 = contentDisposition.indexOf("*=", p1);
                    if (p2 >= 0) {
                        //有的Content-Disposition里面会在文件名后面带上文件名的字符编码
                        int p3 = contentDisposition.indexOf("''", p2);
                        if (p3 >= 0) {
                            charset = contentDisposition.substring(p2 + 2, p3);
                        } else {
                            p3 = p2;
                        }
                        name = contentDisposition.substring(p3 + 2);
                    } else {
                        p2 = contentDisposition.indexOf("=", p1);
                        if (p2 >= 0) {
                            name = contentDisposition.substring(p2 + 1);
                        }
                    }
                }

                name = URLDecoder.decode(name, charset);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (!TextUtils.isEmpty(url) && TextUtils.isEmpty(name)) {
            try {
                File file = new File(url);
                name = file.getName();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (!TextUtils.isEmpty(url) && TextUtils.isEmpty(name)) {
            try {
                name = url.substring(url.lastIndexOf("/") + 1);
                name = URLDecoder.decode(name, charset);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return name;
    }
}

