/**
 * Tests for the Actuator contributions in {@code com.med.qa.actuator}.
 *
 * <p>Both probes are driven through their public Actuator entry points with mocked stores, so the
 * suite stays offline while still asserting the contract operations depends on: a clear
 * {@code UP}/{@code DOWN} status and a reason per component.</p>
 */
package com.med.qa.actuator;
