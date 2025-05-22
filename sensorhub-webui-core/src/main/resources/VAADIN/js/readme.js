window.org_sensorhub_ui_ReadmePanel_ReadmeJS = function() {
    var self = this;
    var contentDiv = document.createElement('div');


    contentDiv.className = 'readme-content';
    contentDiv.style.userSelect = 'text';
    self.getElement().appendChild(contentDiv);

    // Set the readme content
    this.onStateChange = function() {
        //console.log(marked.parse(this.getState().readmeText));
        if (typeof marked !== 'undefined') {
            // Parse markdown and set HTML
            contentDiv.innerHTML = marked.parse(this.getState().readmeText);
        } else {
            console.error('marked.js is not loaded');
            contentDiv.textContent = this.getState().readmeText;
        }
    }

    this.onUnregister = function() {
        if (contentDiv && contentDiv.parentNode) {
            contentDiv.parentNode.removeChild(contentDiv);
        }
        contentDiv = null;
    }
}