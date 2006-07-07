/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License Version
 1.1 (the "License"); you may not use this file except in compliance with
 the License. You may obtain a copy of the License at
 http://www.mozilla.org/MPL/MPL-1.1.html
 
 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.
 
 The Original Code is the "SensorML DataProcessing Engine".
 
 The Initial Developer of the Original Code is the
 University of Alabama in Huntsville (UAH).
 Portions created by the Initial Developer are Copyright (C) 2006
 the Initial Developer. All Rights Reserved.
 
 Contributor(s): 
    Alexandre Robin <robin@nsstc.uah.edu>
 
******************************* END LICENSE BLOCK ***************************/

package org.vast.util;

/**
 * <p><b>Title:</b><br/>
 * Default Exception Handler
 * </p>
 *
 * <p><b>Description:</b><br/>
 * Default exception handler prints exception stack to System.err 
 * </p>
 *
 * <p>Copyright (c) 2005</p>
 * @author Alexandre Robin
 * @date Apr 29, 2006
 * @version 1.0
 */
public class DefaultExceptionHandler implements ExceptionHandler
{

    public String getErrorMessage(Throwable e, String message)
    {
        String exceptionName = e.getClass().getName();
        exceptionName = exceptionName.substring(exceptionName.lastIndexOf('.')+1);
        String className = e.getStackTrace()[0].getClassName();
        className = className.substring(className.lastIndexOf('.')+1);
        message += exceptionName + " in " + className + "." + e.getStackTrace()[0].getMethodName();

        if (e.getMessage() != null)
            message += ": " + e.getMessage().toString() + "\n";

        if (e.getCause() != null)
        {
            message += "\tCause: ";
            message = getErrorMessage(e.getCause(), message);
        }

        return message;
    }


    public void handleException(Throwable e, boolean debug)
    {
        if (debug)
            e.printStackTrace();
        else if (e instanceof RuntimeException)
            e.printStackTrace();
        else
            System.err.println(getErrorMessage(e, "\n"));
    }
}
