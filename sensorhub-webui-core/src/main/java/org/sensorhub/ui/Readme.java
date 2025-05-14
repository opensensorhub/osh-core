package org.sensorhub.ui;

import com.vaadin.annotations.JavaScript;
import com.vaadin.shared.ui.JavaScriptComponentState;
import com.vaadin.ui.AbstractJavaScriptComponent;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.VerticalLayout;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.ui.data.MyBeanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;

@JavaScript({"vaadin://js/jquery.min.js", "vaadin://js/lodash.min.js", "vaadin://js/backbone.min.js", "vaadin://js/joint.js", "vaadin://js/marked.min.js", "vaadin://js/readme.js"})
public class Readme extends AbstractJavaScriptComponent {

    private InputStream readmeIs;
    private static final Logger logger = LoggerFactory.getLogger(Readme.class);

    public static class ReadmeState extends JavaScriptComponentState {
        public String readmeText;
    }

    public Readme(final MyBeanItem<ModuleConfig> beanItem) {

        try {
            // Get the JAR file location from the protection domain
            CodeSource codeSource = beanItem.getBean().getClass().getProtectionDomain().getCodeSource();
            if (codeSource != null) {

                // Convert JAR URL to a file path, navigate to the build directory
                File jarFile = new File(codeSource.getLocation().toURI());
                File buildDir = jarFile.getParentFile().getParentFile();

                // Look for README.md in the resources directory
                File readmeFile = new File(buildDir, "resources/main/README.md");
                if (readmeFile.exists()) {
                    readmeIs = new FileInputStream(readmeFile);
                }
            }

            if (readmeIs == null) {
                try {
                    File buildDir = new File(this.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile().getParentFile();
                    readmeIs = new FileInputStream(new File(buildDir, "resources/main/DefaultREADME.md"));
                    logger.info("Using default README");
                } catch (IOException e) {
                    logger.error("Error opening default readme file", e);
                    return;
                }
            }

            getState().readmeText = new String(readmeIs.readAllBytes());;
            markAsDirty();

        } catch (Exception e) {
            logger.error("Error reading readme file", e);
        } finally {
            try {
                if (readmeIs != null) {
                    readmeIs.close();
                }
            } catch (IOException e) {
                logger.error("Error closing readme stream", e);
            }
            readmeIs = null;
        }
    }

    @Override
    protected ReadmeState getState() {
        return (ReadmeState) super.getState();
    }
}