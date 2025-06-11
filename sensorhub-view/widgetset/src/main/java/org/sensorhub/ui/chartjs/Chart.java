/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui.chartjs;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import com.vaadin.annotations.JavaScript;
import com.vaadin.annotations.StyleSheet;
import com.vaadin.ui.AbstractJavaScriptComponent;
import com.vaadin.ui.JavaScriptFunction;
import elemental.json.JsonArray;


@StyleSheet({"chart.min.css", "nouislider.min.css", "rangeslider.css"})
@JavaScript({"moment.min.js", "chart.min.js", "nouislider.min.js", "connector.js"})
//@JavaScript({"https://cdnjs.cloudflare.com/ajax/libs/Chart.js/2.8.0/Chart.js","connector.js"})
public class Chart extends AbstractJavaScriptComponent
{
    private static final long serialVersionUID = 6600668439443794200L;
    
    public interface SelectListener extends Serializable 
    {
        void onSelect(double[] coords);
    }
    
    public interface SliderChangeListener extends Serializable 
    {
        void onSliderChange(double min, double max);
    }
    
    
    ArrayList<SelectListener> selectListeners = new ArrayList<>();
    ArrayList<SliderChangeListener> sliderListeners = new ArrayList<>();
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    

    public Chart(String domId)
    {
        setId(domId);
        getState().domId = domId;
        
        this.addFunction("onSliderChange", new JavaScriptFunction() {
            private static final long serialVersionUID = 1L;
            @Override
            public void call(JsonArray args) {
                getState().newPlot = false;
                double min = args.getNumber(0);
                double max = args.getNumber(1);
                for (SliderChangeListener l: sliderListeners)
                    l.onSliderChange(min, max);
            }
        });
    } 
    
    
    public Chart(String domId, boolean overlaySlider)
    {
        this(domId);
        this.getState().overlaySlider = true;
    }
    
    
    public void addSelectListener(SelectListener listener)
    {
        selectListeners.add(listener);
    }
    
    
    public void addSliderChangeListener(SliderChangeListener listener)
    {
        sliderListeners.add(listener);
    }
    
    
    @Override
    protected ChartState getState()
    {
        return (ChartState) super.getState();
    }
    
    
    public void setChartConfig(String jsConfig, String jsData)
    {
        getState().newPlot = true;
        getState().config = jsConfig;
        if (jsData != null)
            getState().data = jsData;
    }
    
    
    public void updateChartData(int datasetIdx, String jsData)
    {
        getState().newPlot = false;
        getState().data = jsData;
    }
}
