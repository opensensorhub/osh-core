package org.sensorhub.ui;

import com.vaadin.annotations.JavaScript;
import com.vaadin.server.FontAwesome;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.shared.ui.JavaScriptComponentState;
import com.vaadin.ui.*;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.ui.api.UIConstants;
import org.sensorhub.ui.data.MyBeanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;

public class ReadmePanel extends VerticalLayout {

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
                InputStream readmeIs = beanItem.getBean().getClass().getResourceAsStream("README.md");
                //logger.debug("readmeIs: {}", beanItem.getResource(""));

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
            Label instructionsLabel = new Label(generateInstructions(beanItem), ContentMode.HTML);
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

    private String generateInstructions(final MyBeanItem<ModuleConfig> beanItem) {
        String packagePath = beanItem.getBean().getClass().getPackage().getName().replace(".", "/");
        return "<p>A README file could not be found for this module.</p>\n" +
                "<p>If this is a mistake, please be sure that the module contains a file titled <code>README.md</code> within its resources directory.<br>\n" +
                "<code>src/main/resources/" +
                packagePath +
                "/README.md</code></p>" +
                "<p>Add the following to the module's build.gradle to automatically copy the readme into resources.<br>\n" +
                "If the readme is not in the module's root directory, adjustments may be necessary.</p>\n" +
                "<pre>\n" +
                "tasks.register('copyReadme', Copy) {\n" +
                "\tfrom \"${projectDir}/README.md\"\n" +
                "\tinto \"${projectDir}/src/main/resources/" +
                packagePath + "\"\n" +
                "\tonlyIf { file(\"${projectDir}/README.md\").exists() }\n" +
                "}\n" +
                "\n" +
                "processResources {\n" +
                "\tdependsOn copyReadme\n" +
                "}\n" +
                "</pre>\n";
    }
}