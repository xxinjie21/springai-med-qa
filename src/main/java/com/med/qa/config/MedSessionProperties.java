package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Guard rails of the consultation session lifecycle service.
 *
 * <p>Only the limits the code cannot infer are configurable: how large a listing page may get and how
 * long a session title may be. The title bound protects the {@code VARCHAR(255)} column of
 * {@code med_session} — a title is a display convenience, never clinical content, so truncating the
 * request with a {@code 400} is preferable to letting MySQL reject the insert. The page bound keeps a
 * single listing call from sweeping a whole department out of the database.</p>
 */
@ConfigurationProperties(prefix = "med.session")
public class MedSessionProperties {

    /** Hard ceiling of {@link #maxTitleLength}, imposed by the {@code VARCHAR(255)} column. */
    public static final int TITLE_COLUMN_LENGTH = 255;

    /** Page size applied when a listing request does not specify one. */
    private int defaultPageSize = 20;

    /** Largest page size a listing request may ask for. */
    private int maxPageSize = 100;

    /** Largest accepted session title length in characters. */
    private int maxTitleLength = 200;

    /**
     * Creates the properties with their safe defaults.
     */
    public MedSessionProperties() {
    }

    /**
     * Returns the page size used when the caller omits one.
     *
     * @return default page size, always positive
     */
    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    /**
     * Sets the default page size, failing fast on a non-positive value.
     *
     * @param defaultPageSize page size applied to listing requests without an explicit size,
     *                        must be {@code > 0}
     * @throws IllegalArgumentException when the value is not positive
     */
    public void setDefaultPageSize(int defaultPageSize) {
        Assert.isTrue(defaultPageSize > 0, "defaultPageSize must be positive");
        this.defaultPageSize = defaultPageSize;
    }

    /**
     * Returns the largest page size a caller may request.
     *
     * @return maximum page size, always positive
     */
    public int getMaxPageSize() {
        return maxPageSize;
    }

    /**
     * Sets the maximum page size, failing fast on a non-positive value.
     *
     * @param maxPageSize largest accepted page size, must be {@code > 0}
     * @throws IllegalArgumentException when the value is not positive
     */
    public void setMaxPageSize(int maxPageSize) {
        Assert.isTrue(maxPageSize > 0, "maxPageSize must be positive");
        this.maxPageSize = maxPageSize;
    }

    /**
     * Returns the largest accepted session title length.
     *
     * @return maximum title length in characters, always positive
     */
    public int getMaxTitleLength() {
        return maxTitleLength;
    }

    /**
     * Sets the maximum title length, failing fast outside {@code (0, 255]}.
     *
     * @param maxTitleLength largest accepted title length in characters, must be positive and must
     *                       fit the {@value #TITLE_COLUMN_LENGTH} character column
     * @throws IllegalArgumentException when the value is not positive or exceeds the column width
     */
    public void setMaxTitleLength(int maxTitleLength) {
        Assert.isTrue(maxTitleLength > 0, "maxTitleLength must be positive");
        Assert.isTrue(maxTitleLength <= TITLE_COLUMN_LENGTH,
                "maxTitleLength must not exceed the " + TITLE_COLUMN_LENGTH + " character column");
        this.maxTitleLength = maxTitleLength;
    }
}
