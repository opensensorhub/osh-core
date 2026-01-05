/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.util.Asserts;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.classic.util.LogbackMDCAdapter;


public class ModuleUtils
{
    private static final Logger log = LoggerFactory.getLogger(ModuleUtils.class);
    
    public static final String MODULE_NAME = "Bundle-Name";
    public static final String MODULE_DESC = "Bundle-Description";
    public static final String MODULE_VERSION = "Bundle-Version";
    public static final String MODULE_VENDOR = "Bundle-Vendor";
    public static final String MODULE_BUILD = "Bundle-BuildNumber";
    public static final String MODULE_DEPS = "OSH-Dependencies";
    
    public static final String LOG_MODULE_ID = "MODULE_ID";
    public static final String LOG_MODULE_NAME = "MODULE_NAME";
    public static final String NO_ID_FLAG = "NO_ID";

    /**
     * Prefix that can be used in variable expansions to indicate that the value should be read from the environment
     * variable whose name follows. For example, an input string of "${env;FOO}" would evaluate as the content of the
     * "FOO" environment variable.
     * <p>
     * Note that omitting the prefix will cause the variable expansion to look in the system properties (first) and
     * the environment variables, so this prefix should only be used to force the use of environment variables.
     */
    public static final String ENVIRONMENT_VARIABLE_PREFIX = "env;";

    /**
     * Prefix that can be used in variable expansions to indicate that the value should be read from the system property
     * whose name follows. For example, an input string of "${prop;FOO}" would evaluate as the content of the "FOO"
     * system property.
     * <p>
     * Note that omitting the prefix will cause the variable expansion to look in the system properties (first) and
     * the environment variables, so this prefix should only be used to force the use of system properties.
     */
    public static final String SYSTEM_PROPERTY_PREFIX = "prop;";

    /**
     * Prefix that can be used in variable expansions to indicate that the value should be read from the file at the
     * path that follows. For example, an input string of "${file;/foo/secret}" would evaluate to the content of the
     * "/foo/secret" file on the file system. Note that relative paths are evaluated based on the current working
     * directory of the process at the moment of evaluation. (So absolute paths should be preferred, when possible, to
     * avoid ambiguity.) The contents of the file are assumed to be in the UTF-8 encoding.
     * <p>
     * This will only apply to modules that are explicitly written to use the helper methods here in this class to
     * expand such values.
     */
    public static final String FILE_PREFIX = "file;";
    
    /**
     * Regular expression matching string content that can be expanded at runtime with a value from the runtime
     * environment, such as a system property, environment variable, or the content of a file.
     */
    static final Pattern VARIABLE_EXPANSION_REGEX = Pattern.compile(
    		"\\$?\\$" + // Optional "$" for lazy eval, then a required "$".
    		"\\{" + // Required curly brace
    		"(" + // Optional specifier prefix. Group 1.
    			ENVIRONMENT_VARIABLE_PREFIX + "|" +
    			SYSTEM_PROPERTY_PREFIX + "|" +
    			FILE_PREFIX +
    		")?" +
    		"([^}:]+)" + // Name. Can have anything not "}" or ":". Group 2.
    		"(:([^}]*))?" + // Optional default value after a ":". No "}"s. Group 4. (Group 3 has the ":".)
    		"\\}"); // Closing curly brace
    static final int PREFIX_GROUP = 1;
    static final int NAME_GROUP = 2;
    static final int DEFAULT_VALUE_GROUP = 4;
    
   
    public static Manifest getManifest(Class<?> clazz)
    {
        try
        {
            String classPath = "/" + clazz.getName().replace('.', '/') + ".class";
            URL classUrl = clazz.getResource(classPath);
            if (classUrl != null) 
            {
                String manifestUrl = classUrl.toString().replace(classPath, "/META-INF/MANIFEST.MF");
                return new Manifest(new URL(manifestUrl).openStream());
            }
        }
        catch (IOException e)
        {
            log.trace("Cannot access JAR manifest for {}", clazz);
        }
        
        return null;
    }
    
    
    public static IModuleProvider getModuleInfo(Class<?> clazz)
    {
        Manifest manifest = getManifest(clazz);
        final String name, desc, version, vendor;
        
        if (manifest == null)
        {
            name = MODULE_NAME;
            desc = MODULE_DESC;
            version = MODULE_VERSION;
            vendor = MODULE_VENDOR;
        }
        else
        {
            Attributes attributes = manifest.getMainAttributes();
            name = attributes.getValue(MODULE_NAME);
            desc = attributes.getValue(MODULE_DESC);
            version = attributes.getValue(MODULE_VERSION);
            vendor = attributes.getValue(MODULE_VENDOR);
        }
        
        return new IModuleProvider()
        {
            @Override
            public String getModuleName()
            {
                return name;
            }

            @Override
            public String getModuleDescription()
            {
                return desc;
            }

            @Override
            public String getModuleVersion()
            {
                return version;
            }

            @Override
            public String getProviderName()
            {
                return vendor;
            }

            @Override
            public Class<? extends IModule<?>> getModuleClass()
            {
                return null;
            }

            @Override
            public Class<? extends ModuleConfig> getModuleConfigClass()
            {
                return null;
            }    
        };
    }
    
    
    public static String[] getBundleDependencies(Class<?> clazz)
    {
        Manifest manifest = getManifest(clazz);
        if (manifest == null)
            return new String[0];
            
        String packages = manifest.getMainAttributes().getValue(MODULE_DEPS);
        if (packages == null)
            return new String[0];
        else
            return packages.split(",");
    }
    
    
    public static String getBuildNumber(Class<?> clazz)
    {
        Manifest manifest = getManifest(clazz);
        if (manifest == null)
            return null;
        return manifest.getMainAttributes().getValue(MODULE_BUILD);
    }
    
    
    /**
     * Creates or retrieves logger dedicated to the specified module
     * @param module Module to get logger for
     * @return Logger instance in a separate logging context
     */
    public static Logger createModuleLogger(IModule<?> module)
    {
        Asserts.checkNotNull(module, IModule.class);
        String moduleID = module.getLocalID();

        // if module config wasn't initialized or logback not available, use class logger
        if (moduleID == null || NO_ID_FLAG.equals(moduleID)) {
            return LoggerFactory.getLogger(module.getClass());
        }

        // generate instance ID
        String instanceID = Integer.toHexString(moduleID.hashCode());
        instanceID = instanceID.replace("-", ""); // remove minus sign if any
        
        // create logger in new context
        try
        {
            LoggerContext logContext = new LoggerContext();
            logContext.setName(FileUtils.safeFileName(moduleID));
            logContext.setMDCAdapter(new LogbackMDCAdapter());
            logContext.putProperty(LOG_MODULE_ID, FileUtils.safeFileName(moduleID));
            logContext.putProperty(LOG_MODULE_NAME, module.getName());
            new ContextInitializer(logContext).autoConfig();
            return logContext.getLogger(module.getClass().getCanonicalName() + ":" + instanceID);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Could not configure module logger", e);
        }
    }
    
    /**
     * Performs variable expansion in strings of the form "${name}". This just calls {@link #expand(String, boolean)},
     * passing <code>false</code> for the second parameter, meaning that "$$" prefixed strings will be expanded. See
     * the docs on {@link #expand(String, boolean)} for more details.
     * @param str The input string, optionally containing variables to be expanded
     * @return The expanded string
     */
    public static String expand(String str)
    {
        return expand(str, false);
    }
    
    /**
     * Performs variable expansion on a string by replacing any substrings of the form "${name}" with the value of a
     * system property, environment variable, or file contents. If the variable reference starts with "$$", e.g.
     * "$${name}", then the replacement will only occur if the <code>honorLazyFlag</code> parameter is false.
     * <p>
     * In the simplest form, "${name}", system properties are checked for "name". If there isn't one, then environment
     * variables are checked for "name". If neither exists, the expression will be replaced with an empty string.
     * <p>
     * A prefix can be put in front of the name to force a particular type of lookup, or to specify that the value
     * should be read from a file, e.g. "${env;name}" to only look in environment variables, "${prop;name}" to only
     * look in system properties, or "${file;/path/to/file}" to insert the contents of the given file.
     * <p>
     * A default value can be provided by putting a ":" and the default value, e.g. "${name:some_default}". If the
     * given property (or environment variable or file) cannot be found, then the default value is used instead. Both
     * a prefix and a default value can also be used, e.g. "${env;FOO:bar}".
     * <p>
     * When the "file;" prefix is used, the file contents are assumed to be encoded in UTF-8. Relative paths are
     * evaluated relative to the current working directory of the Java process at the time this method is called.
     * <p>
     * <b>Caveats:</b>
     * <ul>
     *   <li>
     *     This method does not currently support escaping, so expect strange behavior when your string contains a
     *     literal "${" but you do not want variable expansion.
     *   </li>
     *   <li>
     *     On the Windows platform, an absolute path will include a ":" (e.g. "C:\Windows\System32"), but here ":" is
     *     used as the separator for default values. This means that absolute paths are not currently possible on
     *     Windows.
     *   </li>
     * </ul>
     * @param inputString The input string, optionally containing variables to be expanded
     * @param honorLazyFlag Set to false to always fully expand, even if $$ prefix is used
     * @return The fully expanded string unless lazy expansion was requested
     */
    public static String expand(String inputString, boolean honorLazyFlag)
    {
    	// A quick early exit check, to avoid NPEs and to avoid extra work on empty strings
    	if ((inputString == null) || (inputString.length() == 0)) {
    		return inputString;
    	}

    	StringBuilder expandedString = new StringBuilder(inputString.length());
        Matcher matcher = VARIABLE_EXPANSION_REGEX.matcher(inputString);
    	int endOfLastMatch = 0;
        while (matcher.find())
        {
            String variableExpression = matcher.group();
        	int startOfThisMatch = matcher.start();
        	int endOfThisMatch = matcher.end();
        	// Add any intervening characters from end of last match (or beginning of string) up to the start of this
        	// match:
        	expandedString.append(inputString.substring(endOfLastMatch, startOfThisMatch));

        	// If the variable expression starts with "$$", it's a "lazy" expansion. In cases where the value is
        	// being read from a configuration file, we don't want to immediately evaluate the variable expression.
        	// So the honorLazyFlag will be passed as "true" and we will ignore the expression for now. (It is expected
        	// that expand() will be called again by the module itself.)
            if (variableExpression.startsWith("$$") && honorLazyFlag) {
            	expandedString.append(variableExpression);
            } else {
            	String prefix = matcher.group(PREFIX_GROUP);
                String name = matcher.group(NAME_GROUP);
                String defaultValue = matcher.group(DEFAULT_VALUE_GROUP);
                if (defaultValue == null) {
                	defaultValue = "";
                }
                String expandedValue = getValue(prefix, name, defaultValue);
                expandedString.append(expandedValue);
            }
            
            endOfLastMatch = endOfThisMatch;
        }
        // Add any trailing characters
        expandedString.append(inputString.substring(endOfLastMatch, inputString.length()));
        
        return expandedString.toString();
    }
    
    /**
     * Helper method that knows how to get a variable value, based on the type prefix and any provided default value.
     *
     * @param prefix Either a blank string or one of the prefixes above that specifies a source for the value: "env;",
     *   "prop;", or "file;".
     * @param name The name of the system property, environment variable, or file whose content is to be put into the
     *   input string.
     * @param defaultValue A default value to use if the system property, environment variable, or file does not exist
     *   or cannot be read.
     * @return Returns a non-null string representing the requested value.
     */
    private static String getValue(String prefix, String name, String defaultValue) {
    	if ((prefix == null) || (prefix.length() == 0)) {
    		// No prefix provided.
    		// With no prefix, we check system properties first, and if there's not a system property, then we get
    		// an environment variable.
    		String propertyValue = System.getProperty(name);
    		if (propertyValue != null) {
    			return propertyValue;
    		} else {
    			String envVarValue = System.getenv(name);
    			if (envVarValue == null) {
    				return defaultValue;
    			} else {
    				return envVarValue;
    			}
    		}
    	} else if (ENVIRONMENT_VARIABLE_PREFIX.equals(prefix)) {
    		// "env;" prefix provided.
			String envVarValue = System.getenv(name);
			if (envVarValue == null) {
				return defaultValue;
			} else {
				return envVarValue;
			}
    	} else if (SYSTEM_PROPERTY_PREFIX.equals(prefix)) {
    		// "prop;" prefix provided.
    		String propValue = System.getProperty(name);
    		if (propValue == null) {
    			return defaultValue;
    		} else {
    			return propValue;
    		}
    	} else if (FILE_PREFIX.equals(prefix)) {
    		// "file;" prefix provided.
    		File file = new File(name);
    		if (file.canRead()) {
	    		try {
	    			return getFileContents(file);
	    		} catch (IOException ioe) {
	    			log.warn("Failed to read file for string substitution: {}{}", prefix, name, ioe);
	    		}
    		}
    		// If we can't read the file or there's an I/O exception, we'll return the default value.
    		return defaultValue;
    	} else {
    		// Unrecognized prefix provided.
    		log.warn("Unknown prefix for string substitution: {}", prefix);
    		return defaultValue;
    	}
    }

    /**
     * Returns the contents of a file as a string. The file is assumed to be in UTF-8 encoding.
     * <p>
     * This routine assumes that the string is small, and will fit easily in memory. Do not use this for large strings
     * (i.e. anything more than a couple kilobytes).
     * @param file The file containing the string data
     * @return The file content as a String
     * @throws IOException if an error occurs while reading the file
     */
    public static String getFileContents(File file) throws IOException {
		int fileSize = (int) file.length();
		byte[] fileBytes = new byte[fileSize];
		try (FileInputStream fileIn = new FileInputStream(file)) {
			fileIn.read(fileBytes);
		}
		return new String(fileBytes, StandardCharsets.UTF_8);
    }
}
