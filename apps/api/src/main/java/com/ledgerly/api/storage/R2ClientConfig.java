package com.ledgerly.api.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Wires the {@link S3Client} {@link R2StorageClient} depends on. R2's own docs call out
 * {@code chunkedEncodingEnabled(false)} as required — R2 does not support the SDK's default
 * chunked ({@code aws-chunked}) transfer encoding and rejects it with a signature mismatch.
 * Region is the literal string {@code "auto"}: the SDK requires some region be set, but R2
 * does not use it for routing.
 */
@Configuration
@Profile("prod")
public class R2ClientConfig {

  @Bean
  public S3Client r2Client(
      @Value("${ledgerly.storage.r2.account-id}") String accountId,
      @Value("${ledgerly.storage.r2.access-key-id}") String accessKeyId,
      @Value("${ledgerly.storage.r2.secret-access-key}") String secretAccessKey) {
    return S3Client.builder()
        .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
        .region(Region.of("auto"))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
        .build();
  }
}
