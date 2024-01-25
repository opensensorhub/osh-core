/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import org.sensorhub.impl.service.consys.InvalidRequestException.ErrorCode;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;


public class ServiceErrors
{
    public static final String UNSUPPORTED_FORMAT_ERROR_MSG = "Unsupported format: ";
    public static final String NOT_WRITABLE_ERROR_MSG = "Resource is not writable";
    public static final String NOT_FOUND_ERROR_MSG = "Resource not found";
    
    
    private ServiceErrors() {}
    
    
    public static InvalidRequestException unsupportedOperation(String msg)
    {
        return new InvalidRequestException(ErrorCode.UNSUPPORTED_OPERATION, msg);
    }
    
    
    public static InvalidRequestException badRequest(String msg)
    {
        return new InvalidRequestException(ErrorCode.BAD_REQUEST, msg);
    }
    
    
    public static InvalidRequestException invalidPayload(String msg)
    {
        return invalidPayload(msg, null);
    }
    
    
    public static InvalidRequestException invalidPayload(String msg, Throwable cause)
    {
        return new InvalidRequestException(ErrorCode.BAD_PAYLOAD, msg, cause);
    }
    
    
    public static InvalidRequestException requestRejected(String msg)
    {
        return new InvalidRequestException(ErrorCode.REQUEST_REJECTED, msg);
    }
    
    
    public static InvalidRequestException unsupportedFormat(ResourceFormat format)
    {
        return new InvalidRequestException(ErrorCode.BAD_REQUEST, UNSUPPORTED_FORMAT_ERROR_MSG + format);
    }
    
    
    public static InvalidRequestException unsupportedFormat(String format)
    {
        return new InvalidRequestException(ErrorCode.BAD_REQUEST, UNSUPPORTED_FORMAT_ERROR_MSG + format);
    }
    
    
    public static InvalidRequestException notWritable()
    {
        return new InvalidRequestException(ErrorCode.BAD_REQUEST, NOT_WRITABLE_ERROR_MSG);
    }
    
    
    public static InvalidRequestException notWritable(String id)
    {
        return new InvalidRequestException(ErrorCode.BAD_REQUEST, NOT_WRITABLE_ERROR_MSG + ": " + id);
    }
    
    
    public static InvalidRequestException notFound() 
    {
        return new InvalidRequestException(ErrorCode.NOT_FOUND, NOT_FOUND_ERROR_MSG);
    }
    
    
    public static InvalidRequestException notFound(String id) 
    {
        return new InvalidRequestException(ErrorCode.NOT_FOUND, NOT_FOUND_ERROR_MSG + ": " + id);
    }
}
