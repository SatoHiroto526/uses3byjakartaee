package com.example.s3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

// シングルトン化するためにRequestScoped⇒ApplicationScopedに変更
@ApplicationScoped
public class AccessS3 {

    // S3クライアント
    private S3Client s3client;

    // S3認証情報
    private String accessKyeId;
    private String secretAccessKey;

    // リージョン
    private String region;

    // 接続リトライ回数
    private int retryNum;

    // タイムアウト関連
    // リトライ含む総タイムアウト時間
    private int apiCallTimeout;
    // 1回の通信のタイムアウト時間
    private int apiCallAttemptTimeout;

    // プロパティファイル
    private final String s3PropertyFile =  "aws.properties";  

    // 区切り文字定義
    private final String DELIMITER = "/";

    // ロガー
    private static final Logger logger = Logger.getLogger(AccessS3.class.getName());

    @PostConstruct
    public void init() {
        s3ClientBuilder();
    }

    // S3クライアントビルダー
    private boolean s3ClientBuilder() {

        boolean result = false;

        // プロパティファイル読み込み
        Properties properties = new Properties();
        try(InputStream input = getClass().getClassLoader().getResourceAsStream(s3PropertyFile)) {
            if (input == null){
                logger.warning("W:プロパティファイルの読み込みに失敗しました。");
                return result;
            } else {
                properties.load(input);

                // プロパティ取得
                this.accessKyeId =  properties.getProperty("aws.accessKyeId");
                this.secretAccessKey =  properties.getProperty("aws.secretAccessKey");
                this.region =  properties.getProperty("aws.region");
                this.retryNum = Integer.parseInt(properties.getProperty("aws.retryNum"));
                this.apiCallTimeout =  Integer.parseInt(properties.getProperty("aws.apiCallTimeout"));
                this.apiCallAttemptTimeout = Integer.parseInt(properties.getProperty("aws.apiCallAttemptTimeout"));
                logger.info("I:プロパティを取得しました。");

            }
        } catch (IOException e) {
            logger.warning("W:プロパティファイルの読み込みで異常終了しました。");
            logger.warning("詳細:" + e);
            return result;
        }

        try {
            // 認証情報セット
            AwsCredentials credential = AwsBasicCredentials.create(accessKyeId, secretAccessKey);

            // リトライ回数設定
            RetryStrategy retryStrategy = StandardRetryStrategy.builder()
                                                            .maxAttempts(1 + retryNum) // 総試行回数（初回 + リトライ3回）
                                                            .build();

            // ClientOverrideConfigurationオブジェクト生成
            ClientOverrideConfiguration overrideConfig = ClientOverrideConfiguration.builder()
                                                                                    .retryStrategy(retryStrategy) // リトライストラテージーを設定
                                                                                    .apiCallTimeout(Duration.ofSeconds(apiCallTimeout)) // リトライ含む総タイムアウト時間
                                                                                    .apiCallAttemptTimeout(Duration.ofSeconds(apiCallAttemptTimeout)) // 1回の通信のタイムアウト時間
                                                                                    .build();

            // S3クライアント生成
            this.s3client = S3Client.builder()
                                .region(Region.of(region))
                                .credentialsProvider(StaticCredentialsProvider.create(credential))
                                .overrideConfiguration(overrideConfig)
                                .build();
            result = true;
            logger.info("I:S3クライアントを生成しました。");
            return result;
        } catch (SdkClientException e) {
            logger.warning("W:S3クライアントの生成で異常終了しました。（SdkClientException）");
            logger.warning("詳細:" + e);
            return result;
        } catch (Exception e) {
            logger.warning("W:S3クライアントの生成で異常終了しました。（その他例外）");
            logger.warning("詳細:" + e);
            return result;
        }

    }

    // 一覧取得
    public List<String> getS3FileList(String bucket, String prefix) {
        List<String> list = new ArrayList<>();

        try {
            // Requestオブジェクト作成
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .delimiter(DELIMITER)
                        .build();

            // S3オブジェクト一覧取得
            ListObjectsV2Response response = s3client.listObjectsV2(request);

            // ファイル名のみ抽出
            list = response.contents()
                            .stream()
                            .map(S3Object::key)
                            .filter(key -> !key.endsWith("/"))
                            .map(key -> key.substring(prefix.length()))
                            .collect(Collectors.toList());
            
            logger.info("I:取得件数:" + list.size() + "件");
            logger.info("I:S3オブジェクト一覧取得処理を正常終了します。");
            return list;

        } catch (SdkClientException e) {
            logger.warning("W:S3クライアントの生成で異常終了しました。（SdkClientException）");
            logger.warning("詳細:" + e);
            return list;
        } catch (Exception e) {
            list = null;
            logger.warning("W:S3ファイル一覧取得で異常終了しました。");
            logger.warning("詳細:" + e);
            return list; 
        } 
    }


    // ダウンロード
    public InputStream s3FileDownload(String bucket, String filename) {

        // 引数のfilename（フルパス）をプレフィックスとファイル名に分解
        // 文字列の中で最後に出現する「/」の位置を取得
        int lastSlashIndex = filename.lastIndexOf(DELIMITER);
        // 先頭から最後の「/」までを取得
        String prefix = filename.substring(0, lastSlashIndex + 1);
        // 最後の 「/」の次の文字から末尾までを取得
        String file = filename.substring(lastSlashIndex + 1);

        // 一覧取得メソッド（getS3FileList）実行
        List<String> list = getS3FileList(bucket, prefix);
        // ファイルの存在チェック
        if (list.size() == 0) {
            logger.warning("W:s3://" + bucket + DELIMITER + prefix + "配下にオブジェクトが存在しません。");
            return null;
        }
        logger.info("I:s3://" + bucket + DELIMITER + prefix + "配下にオブジェクトの存在を確認しました。");

        // 対象ファイル存在フラグ
        boolean targetFileExistsFlag = false;

        // ループ処理で対象ファイルの存在チェック
        for(String tmpFile : list) {
            if (tmpFile.equals(file)) {
                targetFileExistsFlag = true;
                logger.info("I:s3://" + bucket + DELIMITER + prefix + "配下に対象ファイルの存在を確認しました。");
                break;
            }
        }

        if (!targetFileExistsFlag) {
            logger.warning("W:s3://" + bucket + DELIMITER + prefix + "配下に対象ファイルが存在しません。");
            return null;
        }

        // GetObjectRequest作成
        GetObjectRequest request = GetObjectRequest.builder()
                                                .bucket(bucket)
                                                .key(filename)
                                                .build();

        // S3ファイルをInputStreamでダウンロード
        try{
            ResponseInputStream<GetObjectResponse> s3Object = s3client.getObject(request); 
            if (s3Object == null) {
                logger.warning("W:s3://" + bucket + DELIMITER + filename + "のダウンロードに失敗しました。");
            }

            logger.info("I:S3ファイルダウンロード処理を正常終了します。");
            return s3Object;
        
        } catch (SdkClientException e) {
            logger.warning("W:S3クライアントの生成で異常終了しました。（SdkClientException）");
            logger.warning("詳細:" + e);
            return null;
        } catch(Exception e) {
            logger.warning("W:S3ファイルダウンロード処理で異常終了しました。");
            logger.warning("詳細:" + e);
            return null;
        }
    }


    // アップロード
    public boolean s3FileUpload(String bucket, String filename, InputStream targetFile) {
        boolean result = false;

        try {
            // InputStreamのバイト配列生成
            byte[] data = targetFile.readAllBytes();

            // アップロード用のInputStream生成
            InputStream uploadStream = new ByteArrayInputStream(data);

            // アップロード
            s3client.putObject(req -> req
                    .bucket(bucket)
                    .key(filename),
            RequestBody.fromInputStream(uploadStream, data.length));

            result = true;

            logger.info("I:S3ファイルアップロード処理を正常終了します。");
            return result;

        } catch (SdkClientException e) {
            logger.warning("W:S3クライアントの生成で異常終了しました。（SdkClientException）");
            logger.warning("詳細:" + e);
            return result;
        } catch (Exception e) {
            logger.warning("W:S3ファイルアップロード処理で異常終了しました。");
            logger.warning("詳細:" + e);
            return result;
        } 
    }
}