package pharmacy.domain

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite

/**
 * Entry point for Gradle, which only discovers test classes: runs every .feature file in
 * src/test/resources/pharmacy/domain with the steps in this package.
 */
@Suite
@IncludeEngines("cucumber")
@SelectPackages("pharmacy.domain")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "pharmacy.domain")
class RunCucumberTest
