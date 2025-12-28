package com.example.s3;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;

@Dependent
// S3クライアントプロデューサークラス
public class S3ClientProducer {

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

    // ロガー
    private static final Logger logger = Logger.getLogger(S3ClientProducer.class.getName());

    // S3クライアントビルダー
    @ApplicationScoped
    @Produces
    private S3Client s3ClientBuilder() {

        // プロパティファイル読み込み
        Properties properties = new Properties();
        try(InputStream input = getClass().getClassLoader().getResourceAsStream(s3PropertyFile)) {
            if (input == null){
                logger.warning("W:プロパティファイルの読み込みに失敗しました。");
                return null;
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
            return null;
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
            S3Client s3client = S3Client.builder()
                                .region(Region.of(region))
                                .credentialsProvider(StaticCredentialsProvider.create(credential))
                                .overrideConfiguration(overrideConfig)
                                .build();

            logger.info("I:S3クライアントを生成しました。");
            return s3client;
        } catch (SdkClientException e) {
            logger.warning("W:S3クライアントの生成で異常終了しました。（SdkClientException）");
            logger.warning("詳細:" + e);
            return null;
        } catch (Exception e) {
            logger.warning("W:S3クライアントの生成で異常終了しました。（その他例外）");
            logger.warning("詳細:" + e);
            return null;
        }

    }
    
}