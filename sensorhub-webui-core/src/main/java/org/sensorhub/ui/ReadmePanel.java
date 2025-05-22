package org.sensorhub.ui;

import com.vaadin.annotations.JavaScript;
import com.vaadin.server.FontAwesome;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.shared.ui.JavaScriptComponentState;
import com.vaadin.ui.*;
import org.sensorhub.ui.api.UIConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;

public class ReadmePanel extends VerticalLayout {

    private static final String defaultReadmeHtml = "<p>A README file could not be found for this module.</p>\n" +
            "<p>If this is a mistake, please be sure that…</p>\n" +
            "<ul>\n" +
            "<li>The module contains a file titled <code>README.md</code> within its resources (src &gt; main &gt; resources &gt; {full package name} &gt; README.md).</li>\n" +
            "</ul>\n" +
            "<p>OR</p>\n" +
            "<ul>\n" +
            "<li>\n" +
            "<p>The module contains a file titled <code>README.md</code> in its root directory.</p>\n" +
            "</li>\n" +
            "<li>\n" +
            "<p>Your node’s <code>build.gradle</code> file (the outermost one) includes the following:</p>\n" +
            "<pre><code>allprojects {\n" +
            "\tversion = oshCoreVersion\n" +
            "\ttasks.register('copyReadme', Copy) {\n" +
            "\t\tif (project.hasProperty(\"osgi\")) {\n" +
            "\t\t\tdef classPath = project.osgi.manifest.attributes.get('Bundle-Activator')\n" +
            "\t\t\tif (classPath != null) {\n" +
            "\t\t\t\tclassPath = classPath.tokenize('.').dropRight(1).join('/')\n" +
            "\t\t\t\tfrom \"${projectDir}/README.md\"\n" +
            "\t\t\t\tinto \"${projectDir}/src/main/resources/${classPath}\"\n" +
            "\t\t\t\tonlyIf { file(\"${projectDir}/README.md\").exists() }\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "}\n" +
            "subprojects {  \n" +
            "\t// inject all repositories from included builds if any  \n" +
            "\trepositories.addAll(rootProject.repositories)  \n" +
            "\tplugins.withType(JavaPlugin) {  \n" +
            "\t\tafterEvaluate {  \n" +
            "\t\t\tprocessResources {  \n" +
            "\t\t\t\tdependsOn copyReadme  \n" +
            "\t\t\t}  \n" +
            "\t\t} \n" +
            "\t}\n" +
            "}\n" +
            "</code></pre>\n" +
            "</li>\n" +
            "</ul>\n";

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

        private ReadmeJS(final Class<?> moduleClass) {

            try {
                InputStream readmeIs = moduleClass.getResourceAsStream("README.md");

                if (readmeIs == null) {
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

    public ReadmePanel(final Class<?> moduleClass) {
        ReadmeJS readmeJS = new ReadmeJS(moduleClass);
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