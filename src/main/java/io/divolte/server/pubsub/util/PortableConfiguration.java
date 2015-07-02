package io.divolte.server.pubsub.util;

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.util.Utils;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.pubsub.Pubsub;
import com.google.api.services.pubsub.PubsubScopes;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Optional;

/**
 * Create a Pubsub client using portable credentials.
 */
public class PortableConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PortableConfiguration.class);

    // Default factory method.
    public static Optional<Pubsub> createPubsubClient(String jsonCredsPath, String applicationName) {
        return createPubsubClient(Utils.getDefaultTransport(),
                Utils.getDefaultJsonFactory(),
                jsonCredsPath, applicationName);
    }

    // A factory method that allows you to use your own HttpTransport
    // and JsonFactory.
    public static Optional<Pubsub> createPubsubClient(HttpTransport httpTransport,
                                            JsonFactory jsonFactory, String jsonCredsPath, String applicationName) {
        Preconditions.checkNotNull(httpTransport);
        Preconditions.checkNotNull(jsonFactory);
        Optional<GoogleCredential> credential;

        try {
            credential = Optional.ofNullable(GoogleCredential.fromStream(
                    new FileInputStream(new File(jsonCredsPath)),
                    httpTransport, jsonFactory));
        } catch (IOException ioe) {
            LOG.error("Couldn't initialize Google credentials.");
            credential = Optional.empty();
        }

        return credential.map( (c) -> {

            // In some cases, you need to add the scope explicitly.
            if (c.createScopedRequired()) {
                c = c.createScoped(PubsubScopes.all());
            }

            // Please use custom HttpRequestInitializer for automatic
            // retry upon failures.  We provide a simple reference
            // implementation in the "Retry Handling" section.
            HttpRequestInitializer initializer =
                    new RetryHttpInitializerWrapper(c);
            return new Pubsub.Builder(httpTransport, jsonFactory, initializer)
                    .setApplicationName(applicationName)
                    .build();
        });

    }

    public static GoogleClientSecrets loadClientSecretsFile(JsonFactory jsonFactory, String path) throws IOException {
        return GoogleClientSecrets.load(
                jsonFactory,
                new InputStreamReader(
                        new BufferedInputStream(new FileInputStream(new File(path))), "UTF-8"));
    }

}
