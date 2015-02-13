/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License Version
 1.1 (the "License"); you may not use this file except in compliance with
 the License. You may obtain a copy of the License at
 http://www.mozilla.org/MPL/MPL-1.1.html
 
 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.
 
 The Original Code is the "OGC Service Framework".
 
 The Initial Developer of the Original Code is the VAST team at the University of Alabama in Huntsville (UAH). <http://vast.uah.edu> Portions created by the Initial Developer are Copyright (C) 2007 the Initial Developer. All Rights Reserved. Please Contact Mike Botts <mike.botts@uah.edu> for more information.
 
 Contributor(s): 
    Alexandre Robin <robin@nsstc.uah.edu>
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.ows;

import java.io.*;
import java.util.Map;
import org.vast.xml.DOMHelper;
import org.vast.xml.DOMHelperException;
import org.vast.xml.QName;
import org.w3c.dom.Element;


/**
 * <p>
 * Provides methods to parse a GET or POST OWS request and
 * create an OWSQuery object
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Nov 4, 2005
 * * @param <RequestType> Type of OWS request object generated by this reader
 */
public abstract class AbstractRequestReader<RequestType extends OWSRequest> extends OWSCommonUtils implements OWSRequestReader<RequestType>
{
	protected final static String versionRegex = "^[0-9]+\\.[0-9]+(\\.[0-9]+)?$";
	protected final static String noKVP = "KVP request not supported in ";
    protected final static String noXML = "XML request not supported in ";

    protected String owsVersion = OWSException.VERSION_10;
    
    
    public abstract RequestType readURLParameters(Map<String, String> queryParameters) throws OWSException;
	public abstract RequestType readXMLQuery(DOMHelper domHelper, Element requestElt) throws OWSException;
	
    
	/**
     * Reads common XML request parameters and fill up the OWSQuery accordingly
     * @param dom
     * @param requestElt
     * @param request
     */
    public static void readCommonXML(DOMHelper dom, Element requestElt, OWSRequest request)
    {
        request.setOperation(requestElt.getLocalName());
        request.setService(dom.getAttributeValue(requestElt, "service"));
        request.setVersion(dom.getAttributeValue(requestElt, "version"));
    }
    
    
	public RequestType readURLQuery(String queryString) throws OWSException
	{
	    Map<String, String> queryParams = parseQueryParameters(queryString); 
	    return this.readURLParameters(queryParams);
	}
	
	
    public RequestType readXMLQuery(InputStream input) throws OWSException
	{
		try
		{
            DOMHelper dom = new DOMHelper(input, false);
			return readXMLQuery(dom, dom.getBaseElement());
		}
		catch (DOMHelperException e)
		{
			throw new OWSException(e);
		}
	}
    
    
    /**
     * Helper method to read service, operation name and version from any OWS query string
     * @param request
     * @param queryParameters
	 * @throws OWSException 
     */
    public void readCommonQueryArguments(Map<String, String> queryParameters, OWSRequest request) throws OWSException
    {
    	String val;
    	
    	val = queryParameters.remove("service");
    	if (val != null)
    	    request.setService(val);
    	
    	val = queryParameters.remove("version");
        if (val != null)
            request.setVersion(val);
        
        val = queryParameters.remove("request");
        if (val != null)
            request.setOperation(val);
    }
    
    
	/**
	 * Helper method to read KVP extensions
	 * @param argName
	 * @param argValue
	 * @param request
	 * @throws OWSException
	 */
    public void addKVPExtension(String argName, String argValue, OWSRequest request) throws OWSException
    {
    	request.getExtensions().put(new QName(argName), argValue);
    }
    	
	
    public void checkParameters(OWSRequest request, OWSExceptionReport report) throws OWSException
    {
    	checkParameters(request, report, null);
    }
    
    
	/**
	 * Checks that OWS common mandatory parameters are present
	 * @param request
	 * @param report
	 * @param serviceType 
	 * @throws OWSException 
	 */
	public static void checkParameters(OWSRequest request, OWSExceptionReport report, String serviceType) throws OWSException
	{
		// special case for WMS 1.1
		if (serviceType != null && serviceType.equals(OWSUtils.WMS))
			request.setService(OWSUtils.WMS);
		
		// need SERVICE
		if (request.getService() == null)
			report.add(new OWSException(OWSException.missing_param_code, "SERVICE"));
		
		// must be correct service 
		else if (serviceType != null)
		{
			String reqService = request.getService();
			if (!reqService.equalsIgnoreCase(serviceType))
				report.add(new OWSException(OWSException.invalid_param_code, "SERVICE", reqService, ""));
		}
		
		// need VERSION
		// VERSION is no longer required parameter for all services.  SOS doesn't use it at all
		// in any case it's not mandatory for GetCapabilities
		if (request.getVersion() == null)
		{
			if (request.getOperation() != null && !request.getOperation().equalsIgnoreCase("GetCapabilities"))
				report.add(new OWSException(OWSException.missing_param_code, "VERSION"));
		}
		
		// check version validity
		else if (!request.getVersion().matches(versionRegex))
		{
			OWSException ex = new OWSException(OWSException.invalid_param_code, "VERSION");
			ex.setBadValue(request.getVersion());
			report.add(ex);
		}
		
		// need REQUEST
		if (request.getOperation() == null)
			report.add(new OWSException(OWSException.missing_param_code, "REQUEST"));
	}
}