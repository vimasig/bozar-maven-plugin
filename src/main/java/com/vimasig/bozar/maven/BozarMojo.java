package com.vimasig.bozar.maven;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven plugin that obfuscates the project's JAR file using Bozar backend.
 * 
 * <p>After the package phase builds the JAR, this plugin:</p>
 * <ol>
 *   <li>Uploads the JAR to the Bozar backend</li>
 *   <li>Polls for obfuscation completion</li>
 *   <li>Downloads and replaces the original JAR with the obfuscated version</li>
 * </ol>
 */
@Mojo(name = "obfuscate", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class BozarMojo extends AbstractMojo {

    private static final Gson GSON = new Gson();
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_ATTEMPTS = 300; // 10 minutes max

    // ANSI color codes for terminal output
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String GRAY = "\u001B[90m";

    // Bozar branding
    private static final String BOZAR_PREFIX = BOLD + CYAN + "bozar" + RESET + " ";

    /**
     * Bozar API key for authentication. Required.
     * Can be set via property: -Dbozar.apiKey=bzr_...
     */
    @Parameter(property = "bozar.apiKey", required = true)
    private String apiKey;

    /**
     * Bozar backend server URL.
     */
    @Parameter(property = "bozar.serverUrl", defaultValue = "https://api-bozar.vimasig.com")
    private String serverUrl;

    /**
     * Obfuscation level: "basic", "advanced", or "experimental".
     */
    @Parameter(property = "bozar.obfuscationType", defaultValue = "advanced")
    private String obfuscationType;

    @Parameter
    private Exclusions exclusions;

    /**
     * Skip obfuscation.
     */
    @Parameter(property = "bozar.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${plugin.version}", readonly = true)
    private String pluginVersion;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info(BOZAR_PREFIX + YELLOW + "Obfuscation skipped" + RESET);
            return;
        }

        // Find the project's JAR file
        File jarFile = getProjectJar();
        if (!jarFile.exists()) {
            throw new MojoFailureException("JAR file not found: " + jarFile.getAbsolutePath());
        }

        // Collect runtime dependency JARs
        List<File> dependencyJars = collectDependencyJars();

        getLog().info(BOZAR_PREFIX + "Obfuscating " + CYAN + jarFile.getName() + RESET
                + (dependencyJars.isEmpty() ? "" : GRAY + " (" + dependencyJars.size() + " dependencies)" + RESET));

        String configJson = buildConfigJson();

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            // Step 1: Upload JAR and dependencies, start obfuscation
            String sessionId = uploadJar(httpClient, jarFile, dependencyJars, configJson);
            getLog().info(BOZAR_PREFIX + "Session ID: " + GRAY + sessionId + RESET);

            // Step 2: Poll for completion
            pollForCompletion(httpClient, sessionId);

            // Step 3: Download and replace JAR
            downloadAndReplaceJar(httpClient, sessionId, jarFile);
            getLog().info(BOZAR_PREFIX + GREEN + "Obfuscation complete: " + RESET + CYAN + jarFile.getName() + RESET);

        } catch (IOException e) {
            getLog().error(BOZAR_PREFIX + RED + e.getMessage() + RESET);
            throw new MojoExecutionException("Obfuscation failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Obfuscation interrupted", e);
        }
    }

    private File getProjectJar() {
        String finalName = project.getBuild().getFinalName();
        String buildDir = project.getBuild().getDirectory();
        return new File(buildDir, finalName + ".jar");
    }

    private List<File> collectDependencyJars() {
        List<File> jars = new ArrayList<>();
        if (project.getArtifacts() == null) {
            return jars;
        }
        for (Artifact artifact : project.getArtifacts()) {
            String scope = artifact.getScope();
            if ((Artifact.SCOPE_COMPILE.equals(scope) || Artifact.SCOPE_RUNTIME.equals(scope))
                    && "jar".equals(artifact.getType())) {
                File file = artifact.getFile();
                if (file != null && file.exists()) {
                    jars.add(file);
                }
            }
        }
        return jars;
    }

    private String buildConfigJson() {
        JsonObject json = new JsonObject();
        json.addProperty("obfuscationType", obfuscationType);

        if (exclusions != null) {
            JsonObject excl = new JsonObject();
            if (exclusions.classes != null && !exclusions.classes.isEmpty()) {
                JsonArray arr = new JsonArray();
                exclusions.classes.forEach(arr::add);
                excl.add("classes", arr);
            }
            if (exclusions.methods != null && !exclusions.methods.isEmpty()) {
                JsonArray arr = new JsonArray();
                exclusions.methods.forEach(arr::add);
                excl.add("methods", arr);
            }
            if (exclusions.fields != null && !exclusions.fields.isEmpty()) {
                JsonArray arr = new JsonArray();
                exclusions.fields.forEach(arr::add);
                excl.add("fields", arr);
            }
            json.add("exclusions", excl);
        }

        return GSON.toJson(json);
    }

    private String uploadJar(CloseableHttpClient httpClient, File jarFile, List<File> dependencyJars, String configJson) 
            throws IOException, MojoExecutionException {
        
        String uploadUrl = serverUrl + "/api/v1/obfuscate";
        HttpPost httpPost = new HttpPost(uploadUrl);
        httpPost.setHeader("X-API-Key", apiKey);
        httpPost.setHeader("X-Bozar-Plugin-Version", pluginVersion != null ? pluginVersion : "unknown");
        httpPost.setHeader("X-Java-Version", System.getProperty("java.version"));

        MultipartEntityBuilder builder = MultipartEntityBuilder.create()
                .addBinaryBody("file", jarFile, ContentType.APPLICATION_OCTET_STREAM, jarFile.getName())
                .addTextBody("config", configJson, ContentType.APPLICATION_JSON);

        for (File dep : dependencyJars) {
            builder.addBinaryBody("dependencies", dep, ContentType.APPLICATION_OCTET_STREAM, dep.getName());
        }

        httpPost.setEntity(builder.build());

        return httpClient.execute(httpPost, response -> {
            int statusCode = response.getCode();
            String responseBody = EntityUtils.toString(response.getEntity());

            if (statusCode != 200) {
                throw new IOException(parseServerError("Upload", statusCode, responseBody));
            }

            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (!json.has("sessionId")) {
                throw new IOException("Invalid response: missing sessionId");
            }
            return json.get("sessionId").getAsString();
        });
    }

    private void pollForCompletion(CloseableHttpClient httpClient, String sessionId) 
            throws IOException, InterruptedException, MojoExecutionException {
        
        String statusUrl = serverUrl + "/api/v1/obfuscate/" + sessionId;
        
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            HttpGet httpGet = new HttpGet(statusUrl);
            httpGet.setHeader("X-API-Key", apiKey);

            SessionStatus status = httpClient.execute(httpGet, response -> {
                int statusCode = response.getCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                if (statusCode != 200) {
                    throw new IOException(parseServerError("Status check", statusCode, responseBody));
                }

                return GSON.fromJson(responseBody, SessionStatus.class);
            });

            // Log any new messages
            if (status.logs != null) {
                for (String log : status.logs) {
                    logBozarMessage(log);
                }
            }

            if (status.finished) {
                if (!status.success) {
                    throw new IOException("Obfuscation failed. Check logs above.");
                }
                return;
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }

        throw new IOException("Obfuscation timed out after " + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000) + " seconds");
    }

    /**
     * Parses and formats a log message from the Bozar backend.
     * Input format: "LEVEL|message" (e.g., "INFO|Transforming classes...")
     */
    private void logBozarMessage(String rawLog) {
        String level;
        String message;
        
        int pipeIndex = rawLog.indexOf('|');
        if (pipeIndex > 0) {
            level = rawLog.substring(0, pipeIndex).toUpperCase();
            message = rawLog.substring(pipeIndex + 1);
        } else {
            level = "INFO";
            message = rawLog;
        }

        String formattedMessage;
        switch (level) {
            case "ERR":
            case "ERROR":
                formattedMessage = BOZAR_PREFIX + RED + "[!] " + message + RESET;
                break;
            case "WARN":
            case "WARNING":
                formattedMessage = BOZAR_PREFIX + YELLOW + "[!] " + message + RESET;
                break;
            case "DEBUG":
                formattedMessage = BOZAR_PREFIX + GRAY + message + RESET;
                break;
            default:
                formattedMessage = BOZAR_PREFIX + message;
                break;
        }

        // Use appropriate Maven log level
        switch (level) {
            case "ERR":
            case "ERROR":
                getLog().error(formattedMessage);
                break;
            case "WARN":
            case "WARNING":
                getLog().warn(formattedMessage);
                break;
            case "DEBUG":
            case "INFO":
            default:
                getLog().info(formattedMessage);
                break;
        }
    }

    private void downloadAndReplaceJar(CloseableHttpClient httpClient, String sessionId, File targetJar) 
            throws IOException {
        
        String downloadUrl = serverUrl + "/api/v1/obfuscate/" + sessionId + "/download";
        HttpGet httpGet = new HttpGet(downloadUrl);
        httpGet.setHeader("X-API-Key", apiKey);

        httpClient.execute(httpGet, response -> {
            int statusCode = response.getCode();
            if (statusCode != 200) {
                String errorBody = EntityUtils.toString(response.getEntity());
                throw new IOException(parseServerError("Download", statusCode, errorBody));
            }

            // Write the obfuscated JAR to a temp file first
            File tempFile = new File(targetJar.getParentFile(), targetJar.getName() + ".obfuscated.tmp");
            try (InputStream is = response.getEntity().getContent();
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            // Replace original JAR
            if (!targetJar.delete()) {
                throw new IOException("Failed to delete original JAR: " + targetJar);
            }
            if (!tempFile.renameTo(targetJar)) {
                throw new IOException("Failed to rename obfuscated JAR");
            }

            return null;
        });
    }

    /**
     * DTO for session status response.
     */
    private static String parseServerError(String operation, int statusCode, String responseBody) {
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            String error   = json.has("error")   ? json.get("error").getAsString()   : "HTTP " + statusCode;
            String message = json.has("message") ? json.get("message").getAsString() : responseBody;
            return operation + " failed [" + error + "]: " + message;
        } catch (Exception ignored) {
            return operation + " failed (HTTP " + statusCode + "): " + responseBody;
        }
    }

    /**
     * Maven-friendly exclusion configuration mapped from XML.
     */
    public static class Exclusions {
        @Parameter
        private List<String> classes;

        @Parameter
        private List<String> methods;

        @Parameter
        private List<String> fields;
    }

    private static class SessionStatus {
        String sessionId;
        String[] logs;
        boolean finished;
        boolean success;
    }
}
