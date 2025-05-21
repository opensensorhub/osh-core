package org.sensorhub.ui;

import com.vaadin.annotations.JavaScript;
import com.vaadin.annotations.StyleSheet;
import com.vaadin.event.CollapseEvent;
import com.vaadin.event.MouseEvents;
import com.vaadin.server.FontAwesome;
import com.vaadin.server.ThemeResource;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.shared.ui.JavaScriptComponentState;
import com.vaadin.ui.*;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.ui.api.UIConstants;
import org.sensorhub.ui.data.MyBeanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.security.CodeSource;
import org.sensorhub.ui.api.UIConstants.*;


public class ReadmePanel extends VerticalLayout {

    private static final String defaultReadmeHtml = "<p>A README file could not be found for this module.</p>\n" +
            "<p>If this is a mistake, please be sure that...</p>\n" +
            "<ul>\n" +
            "<li>The module contains a file titled <code>README.md</code> within its build (build &gt; resources &gt; main &gt; README.md).</li>\n" +
            "</ul>\n" +
            "<p>OR</p>\n" +
            "<ul>\n" +
            "<li>The module contains a file titled <code>README.md</code> in its root directory.</li>\n" +
            "<li><p>Your node&#39;s <code>build.gradle</code> file (the outermost one) includes the following:\n" +
            "   </p>\n" +
            "<pre><code><span class=\"hljs-keyword\">allprojects</span> {\n" +
            "    version = oshCoreVersion\n" +
            "    tasks.register(<span class=\"hljs-string\">'copyReadme'</span>, <span class=\"hljs-keyword\">Copy</span>) {\n" +
            "        <span class=\"hljs-keyword\">from</span> <span class=\"hljs-string\">\"${projectDir}/README.md\"</span>\n" +
            "        <span class=\"hljs-keyword\">into</span> <span class=\"hljs-string\">\"${buildDir}/resources/main\"</span>\n" +
            "        onlyIf { <span class=\"hljs-keyword\">file</span>(<span class=\"hljs-string\">\"${projectDir}/README.md\"</span>).exists() }\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "<span class=\"hljs-keyword\">subprojects</span> {\n" +
            "    <span class=\"hljs-comment\">// inject all repositories from included builds if any</span>\n" +
            "    <span class=\"hljs-keyword\">repositories</span>.addAll(rootProject.<span class=\"hljs-keyword\">repositories</span>)\n" +
            "    plugins.withType(JavaPlugin) {\n" +
            "        processResources {\n" +
            "            dependsOn copyReadme\n" +
            "        }\n" +
            "    }\n" +
            "}\n" +
            "</code></pre></li>\n" +
            "</ul>";

    // This determines which tab is visible
    // Hack needed for desired accordion behavior in this older version of Vaadin
    private boolean visibleTab = false;

    @JavaScript({"vaadin://js/jquery.min.js", "vaadin://js/lodash.min.js", "vaadin://js/backbone.min.js", "vaadin://js/joint.js", "vaadin://js/marked.min.js", "vaadin://js/readme.js"})
    public class ReadmeJS extends AbstractJavaScriptComponent {
        private InputStream readmeIs;
        private static final Logger logger = LoggerFactory.getLogger(ReadmePanel.class);
        private boolean hasContent = false;

        public static class ReadmeState extends JavaScriptComponentState {
            public String readmeText;
        }

        private ReadmeJS(final MyBeanItem<ModuleConfig> beanItem) {

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
                    //readmeIs = new ByteArrayInputStream(defaultReadme.getBytes());
                    //addComponent
                    hasContent = false;
                } else {
                    hasContent = true;
                    getState().readmeText = new String(readmeIs.readAllBytes());
                    markAsDirty();
                }

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

        public boolean hasContent() {
            return hasContent;
        }
    }

    public ReadmePanel(final MyBeanItem<ModuleConfig> beanItem) {
        ReadmeJS readmeJS = new ReadmeJS(beanItem);
        if (readmeJS.hasContent()) {
            // Use JS markdown parser if a readme exists
            addComponent(readmeJS);
        } else {
            // Otherwise, display instructions for adding a readme file
            var header = new HorizontalLayout();
            header.setSpacing(true);
            Label title = new Label("No README");
            title.addStyleName(UIConstants.STYLE_H2);
            header.addComponent(title);
            addComponent(header);

            Button detailsBtn = new Button("Detailed Instructions");
            detailsBtn.setIcon(FontAwesome.CARET_RIGHT);
            //detailsBtn.setWidth(100.0f, Unit.PERCENTAGE);

            VerticalLayout instructions = new VerticalLayout();
            instructions.setMargin(true);
            instructions.setSpacing(true);
            Label instructionsLabel = new Label(defaultReadmeHtml, ContentMode.HTML);
            instructions.addComponent(instructionsLabel);
            instructions.setVisible(false);
            instructions.addStyleNames("v-csslayout-well", "v-scrollable");

            detailsBtn.addClickListener(event -> {
                if (visibleTab) {
                    detailsBtn.setIcon(FontAwesome.CARET_RIGHT);
                    instructions.setVisible(false);
                    visibleTab = false;
                } else {
                    detailsBtn.setIcon(FontAwesome.CARET_DOWN);
                    instructions.setVisible(true);
                    visibleTab = true;
                }
            });

            addComponent(detailsBtn);
            addComponent(instructions);

        }
    }
}