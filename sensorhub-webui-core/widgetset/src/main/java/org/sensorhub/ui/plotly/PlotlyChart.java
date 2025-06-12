/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui.plotly;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.logging.Logger;
import com.vaadin.annotations.JavaScript;
import com.vaadin.ui.AbstractJavaScriptComponent;
import com.vaadin.ui.JavaScriptFunction;
import elemental.json.JsonArray;


@JavaScript({"plotly-latest.min.js","connector.js"})
public class PlotlyChart extends AbstractJavaScriptComponent
{
    private static final long serialVersionUID = 6600668439443794200L;
    
    public interface ZoomListener extends Serializable 
    {
        void onZoom(double[] xRange);
    }
    
    
    ArrayList<ZoomListener> zoomListeners;
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    

    public PlotlyChart(String domId)
    {
        setId(domId);
        getState().domId = domId;
        
        this.zoomListeners = new ArrayList<>();
        this.addFunction("onZoom", new JavaScriptFunction() {
            private static final long serialVersionUID = 1L;
            @Override
            public void call(JsonArray args) {
                String minVal = args.getString(0);
                String maxVal = args.getString(1);
                if (minVal == null || maxVal == null)
                    return;
                
                try
                {
                    //System.out.println(minVal + "/" + maxVal);
                    double[] xRange = new double[2];
                    xRange[0] = dateTimeFormat.parse(minVal).getTime() / 1000.;
                    xRange[1] = dateTimeFormat.parse(maxVal).getTime() / 1000.;
                    
                    for (ZoomListener l: zoomListeners)
                        l.onZoom(xRange);
                }
                catch (ParseException e)
                {
                    Logger.getAnonymousLogger().severe(e.getMessage());
                }
            }
        });
    }    
    
    
    public void addZoomListener(ZoomListener listener)
    {
        zoomListeners.add(listener);
    }
    
    
    @Override
    protected PlotlyState getState()
    {
        return (PlotlyState) super.getState();
    }
    
    
    public void setChartConfig(String jsConfig)
    {
        getState().newPlot = true;
        getState().config = jsConfig;
    }
    
    
    public void setChartData(String jsData)
    {
        //getState().newPlot = false;
        getState().data = jsData;
    }
}
