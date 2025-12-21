package com.example.action;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.logging.Logger;

import com.example.s3.AccessS3;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.Part;
import lombok.Getter;
import lombok.Setter;

@Named
@RequestScoped
@Getter
@Setter
public class UseS3Action {

    private String bucket = "uses3byjakartaee";

    private String prefix = "com/example/";

    private String file;

    private List<String> s3ObjectList;

    private Part inputFile;

    // ロガー
    private static final Logger logger = Logger.getLogger(UseS3Action.class.getName());

    @Inject
    private AccessS3 s3;

    @PostConstruct
    public void init () {
        this.s3ObjectList = s3.getS3FileList(bucket, prefix);
    }

    public void s3FileDownload() {
        String filename = prefix + file;
        ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();

        try {
            ec.responseReset();
            ec.setResponseContentType("application/octet-stream");

            // 日本語ファイル名をURLエンコード
            String encodedFilename = URLEncoder.encode(file, "UTF-8").replaceAll("\\+", "%20");

            ec.setResponseHeader(
                "Content-Disposition",
                "attachment; filename*=UTF-8''" + encodedFilename
            );

            try (OutputStream out = ec.getResponseOutputStream()) {

            s3.s3FileDownload(bucket, filename, out);
            out.flush();
            }

            FacesContext.getCurrentInstance().responseComplete();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void s3FileUpload() {
        String originalFileName = getUtf8FileName(inputFile);
        logger.info("I:アップロードファイル名:" + originalFileName);
        logger.info("I:アップロードサイズ:" + inputFile.getSize() + "バイト");

        this.file = originalFileName;

        String filename = prefix + file;

        try(InputStream input = inputFile.getInputStream()) {
            boolean uploadResult = s3.s3FileUpload(bucket, filename, input);
            if (!uploadResult) {
                logger.warning("W:アップロード処理で例外が発生しました。");
            } else {
                logger.info("I:アップロード処理が正常終了しました。");
                init();
            }
        } catch (IOException e) {
            logger.warning("W:アップロード処理で例外が発生しました。");
        }
    }

    private String getUtf8FileName(Part part) {
        String submittedFileName = part.getSubmittedFileName();
        if (submittedFileName != null) {
            try {
                return new String(submittedFileName.getBytes("ISO-8859-1"), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                // 変換できなかった場合は元の文字列を返す
                return submittedFileName;
            }
        }
        return null;
    }
}