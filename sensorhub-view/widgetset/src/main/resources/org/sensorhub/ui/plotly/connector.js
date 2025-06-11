window.org_sensorhub_ui_plotly_PlotlyChart = function () {
    this.onStateChange = function () {
        // read state
        var newPlot = this.getState().newPlot;
        var domId = this.getState().domId;        
        
        if (newPlot) {
            var configString = this.getState().config;
            eval(configString);
            
            var dataString = this.getState().data;
            var data = eval(dataString);

            // set chart context
            Plotly.newPlot(domId, data, layout, {displayModeBar: false});
            
            var self = this;
            var plot = document.getElementById(domId);
            var timer = null;
            plot.on('plotly_relayout', function(e) {
               if (timer != null)
                   clearTimeout(timer);
               timer = setTimeout(function () {
                   var xRange = plot.layout.xaxis.range;
                   if (xRange != null)
                     self.onZoom(xRange[0], xRange[1]);    
               }, 400);               
            });
        } else {
            var dataString = this.getState().data;
            var data = eval(dataString);
            Plotly.update(domId, data);
        }        
    };
};