package io.vepr.contracts.openapi;

/**
 * Classpath resource locations of OpenAPI specifications bundled within this artifact.
 *
 * <p>Callers can access these specifications at runtime using {@link Class#getResourceAsStream(String)}
 * or {@link ClassLoader#getResourceAsStream(String)}.
 */
public final class OpenApiSpecs {

    /** Bank-facing partner REST API specification classpath path. */
    public static final String PARTNER = "/openapi/partner.yaml";

    /** Phone-facing device REST API specification classpath path. */
    public static final String DEVICE = "/openapi/device.yaml";

    /** Shared components referenced across specifications classpath path. */
    public static final String COMMON_COMPONENTS = "/openapi/components/common.yaml";

    private OpenApiSpecs() {}
}
