package cn.keking.utils;

import cn.keking.config.ConfigConstants;
import cn.keking.model.FileAttribute;
import cn.keking.model.ReturnResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mola.galimatias.GalimatiasParseException;
import okhttp3.*;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static cn.keking.utils.KkFileUtils.isFtpUrl;
import static cn.keking.utils.KkFileUtils.isHttpUrl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.RestTemplate;
/**
 * @author yudian-it
 */
public class DownloadUtils {

    private final static Logger logger = LoggerFactory.getLogger(DownloadUtils.class);
    private static final String fileDir = ConfigConstants.getFileDir();
    private static final String URL_PARAM_FTP_USERNAME = "ftp.username";
    private static final String URL_PARAM_FTP_PASSWORD = "ftp.password";
    private static final String URL_PARAM_FTP_CONTROL_ENCODING = "ftp.control.encoding";
    private static final RestTemplate restTemplate = new RestTemplate();

    private static OkHttpClient client = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();


    public static boolean fileExist(FileAttribute fileAttribute, String fileName) {
        // 忽略ssl证书
        String urlStr = null;
        try {
            SslUtils.ignoreSsl();
            urlStr = fileAttribute.getUrl().replaceAll("\\+", "%20");
        } catch (Exception e) {
            logger.error("忽略SSL证书异常:", e);
        }
        String realPath = getRelFilePath(fileName, fileAttribute);
        return KkFileUtils.isExist(realPath);
    }
    /**
     * @param fileAttribute fileAttribute
     * @param fileName      文件名
     * @return 本地文件绝对路径
     */
    public static ReturnResponse<String> downLoad(FileAttribute fileAttribute, String fileName) {
        // 忽略ssl证书
        String urlStr = null;
        try {
            SslUtils.ignoreSsl();
            urlStr = fileAttribute.getUrl().replaceAll("\\+", "%20");
        } catch (Exception e) {
            logger.error("忽略SSL证书异常:", e);
        }
        ReturnResponse<String> response = new ReturnResponse<>(0, "下载成功!!!", "");
        String realPath = getRelFilePath(fileName, fileAttribute);
        logger.info("begin download file, store path: {}", realPath);

        // 判断是否非法地址
        if (KkFileUtils.isIllegalFileName(realPath)) {
            response.setCode(1);
            response.setContent(null);
            response.setMsg("下载失败:文件名不合法!" + urlStr);
            return response;
        }
        if (!KkFileUtils.isAllowedUpload(realPath)) {
            response.setCode(1);
            response.setContent(null);
            response.setMsg("下载失败:不支持的类型!" + urlStr);
            return response;
        }
        assert urlStr != null;
        if (urlStr.contains("?fileKey=")) {
            response.setContent(fileDir + fileName);
            response.setMsg(fileName);
            return response;
        }
        // 如果文件是否已经存在、且不强制更新，则直接返回文件路径
        if (KkFileUtils.isExist(realPath) && !fileAttribute.forceUpdatedCache()) {
            logger.info("文件已存在，直接使用, file: {}", realPath);
            response.setContent(realPath);
            response.setMsg(fileName);
            return response;
        }
        try {
            URL url = WebUtils.normalizedURL(urlStr);
            if (!fileAttribute.getSkipDownLoad()) {
                if (isHttpUrl(url)) {
                    File realFile = new File(realPath);
                    RequestCallback requestCallback = request -> {
                        request.getHeaders()
                                .setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
                       String proxyAuthorization = fileAttribute.getKkProxyAuthorization();
                        if(StringUtils.hasText(proxyAuthorization)){
                          Map<String,String>  proxyAuthorizationMap = mapper.readValue(proxyAuthorization, Map.class);
                          proxyAuthorizationMap.entrySet().forEach(entry-> request.getHeaders().set(entry.getKey(), entry.getValue()));
                        }
                    };
//                    urlStr = URLDecoder.decode(urlStr, StandardCharsets.UTF_8.name());
                    Request request = new Request.Builder().url(urlStr).build();
                    client.newCall(request).enqueue(new Callback() {
                        public void onFailure(Call call, IOException e) {
                            logger.error("下载文件失败", e);
                        }

                        public void onResponse(Call call, Response response) throws IOException {
                            if (!response.isSuccessful()) {
                                logger.error("Failed to download file: {}", response);
                                throw new IOException("Failed to download file: " + response);
                            }
                            FileOutputStream fos = new FileOutputStream(realFile);
                            fos.write(response.body().bytes());
                            fos.close();
                            logger.info("end download file, store path: {}", realPath);
                        }
                    });
//                    restTemplate.execute(urlStr, HttpMethod.GET, requestCallback, fileResponse -> {
//                        FileUtils.copyToFile(fileResponse.getBody(), realFile);
//                        return null;
//                    });
                } else if (isFtpUrl(url)) {
                    String ftpUsername = WebUtils.getUrlParameterReg(fileAttribute.getUrl(), URL_PARAM_FTP_USERNAME);
                    String ftpPassword = WebUtils.getUrlParameterReg(fileAttribute.getUrl(), URL_PARAM_FTP_PASSWORD);
                    String ftpControlEncoding = WebUtils.getUrlParameterReg(fileAttribute.getUrl(), URL_PARAM_FTP_CONTROL_ENCODING);
                    FtpUtils.download(fileAttribute.getUrl(), realPath, ftpUsername, ftpPassword, ftpControlEncoding);
                } else {
                    response.setCode(1);
                    response.setMsg("url不能识别url" + urlStr);
                }
            }
            response.setContent(realPath);
            response.setMsg(fileName);
            return response;
        } catch (IOException | GalimatiasParseException e) {
            logger.error("文件下载失败，url：{}", urlStr);
            response.setCode(1);
            response.setContent(null);
            if (e instanceof FileNotFoundException) {
                response.setMsg("文件不存在!!!");
            } else {
                response.setMsg(e.getMessage());
            }
            return response;
        }
    }


    /**
     * 获取真实文件绝对路径
     *
     * @param fileName 文件名
     * @return 文件路径
     */
    private static String getRelFilePath(String fileName, FileAttribute fileAttribute) {
        String type = fileAttribute.getSuffix();
        if (null == fileName) {
            UUID uuid = UUID.randomUUID();
            fileName = uuid + "." + type;
        } else { // 文件后缀不一致时，以type为准(针对simText【将类txt文件转为txt】)
            fileName = fileName.replace(fileName.substring(fileName.lastIndexOf(".") + 1), type);
        }

        String realPath = fileDir + fileName;
        File dirFile = new File(fileDir);
        if (!dirFile.exists() && !dirFile.mkdirs()) {
            logger.error("创建目录【{}】失败,可能是权限不够，请检查", fileDir);
        }
        return realPath;
    }

}
