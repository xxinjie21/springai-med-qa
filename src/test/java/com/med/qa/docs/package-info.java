/**
 * Guard tests for the shipped documentation.
 *
 * <p>The README and the deployment handbook are part of the deliverable: they advertise endpoints,
 * error codes, environment variables and image coordinates that must stay in step with the code and
 * the deployment descriptors. This package parses the documents as plain text and pins those
 * contracts, so a renamed endpoint, a dropped error code or a health-check path that no longer
 * exists fails {@code mvn test} instead of sending an operator down a dead end.</p>
 */
package com.med.qa.docs;
