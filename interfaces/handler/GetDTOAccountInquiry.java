/*
 * GetDTOAccountInquiry.java
 *
 */

package com.cbic.ar.interfaces.handler;

import java.util.ArrayList;
import java.util.HashMap;

import net.inov.biz.server.IBIZException;
import net.inov.biz.server.ServiceHandlerException;
import net.inov.tec.beans.ModelBean;
import net.inov.tec.data.JDBCData;
import net.inov.tec.date.StringDate;
import net.inov.tec.web.cmm.AdditionalParams;

import com.iscs.ar.ElectronicPayment;
import com.iscs.ar.interfaces.DTOAccount;
import com.iscs.common.tech.biz.InnovationIBIZHandler;
import com.iscs.common.tech.log.Log;
import com.iscs.common.utility.DateTools;
import com.iscs.common.utility.InnovationUtils;
import com.iscs.common.utility.StringTools;

/** Builds an AccountInquiry bean
 *
 * @author  patriciat
 */
public class GetDTOAccountInquiry extends InnovationIBIZHandler {
    // Create an object for logging messages
    
    
    /** Creates a new instance of GetDTOAccountInquiry
     * @throws Exception never
     */
    public GetDTOAccountInquiry() throws Exception {
    }
    
    /** Processes a generic service request.
     * @return the current response bean
     * @throws IBIZException when an error requiring user attention occurs
     * @throws ServiceHandlerException when a critical error occurs
     */
    public ModelBean process() throws IBIZException, ServiceHandlerException {
        try {
            // Log a greeting
            Log.debug("Processing GetDTOAccountInquiry...");
            ModelBean rs = this.getHandlerData().getResponse();
            JDBCData data = this.getHandlerData().getConnection();
			ModelBean responseParams = rs.getBean("ResponseParams");
            StringDate todayDt = DateTools.getStringDate(responseParams);
			ModelBean userInfo = responseParams.getBean("UserInfo");
			AdditionalParams ap = new AdditionalParams(this.getHandlerData().getRequest());
			
			String isExternal = ap.gets("IsExternal", "Yes");
                      			
            // Load AccountInquiry Data into DTOAccountInquiry
            ModelBean[] accountInquiries = rs.getBeans("AccountInquiry");
            ElectronicPayment electronicPayment = new ElectronicPayment();
            for (ModelBean accountInquiry : accountInquiries) {
            	
    			checkContainerSecurity(data, todayDt, userInfo, accountInquiry, "You do not have security to access the accounts of this Customer.");
    			
                ModelBean dtoAccountInquiry = DTOAccount.loadAccountInquiry(accountInquiry);            	
                
                // Modify the original payment source id so that it can be selected during a make payment as an 'on-file' selection
        		ModelBean recurringPaymentSource = dtoAccountInquiry.getBean("ElectronicPaymentSource");
        		if (recurringPaymentSource != null) {
        			String sourceId = recurringPaymentSource.getId();
        			boolean includeInd = ElectronicPayment.includePaymentSource(data, accountInquiry, recurringPaymentSource);        			
        			if( includeInd){        				
	        			String recurringKey = electronicPayment.getReceiptPaymentSourceKey(recurringPaymentSource);
	        			String hashedKey = InnovationUtils.Crypto.getHash(recurringKey);
	        			recurringPaymentSource.setValue("id", hashedKey);
        			} else {
        				// if recurring payment is reversed, remove it from the account inquiry bean itself so it doesn't show up as an 'on-file' selection
        				dtoAccountInquiry.deleteBeanById("ElectronicPaymentSource", sourceId);
        			}
        		}
                                
                // Get the unique list of Electronic Payment Sources
                ModelBean[] paymentSources = electronicPayment.getReceiptPaymentSources(data, accountInquiry);
                if (paymentSources.length == 0) {
                	// No additional payment sources besides the recurring one, so take a look in prior term and see if we can obtain anymore
                	if (accountInquiry.gets("AccountPrevRef").length() > 0) {
						ModelBean prevAccount = data.selectMiniBean("Account", accountInquiry.gets("AccountPrevRef"));
						ModelBean[] prevPaymentSources = electronicPayment.getReceiptPaymentSources(data, prevAccount);
						if (prevPaymentSources.length > 0) {
							paymentSources = combinePaymentSources(paymentSources, prevPaymentSources);
						}
                	}
                }

                // Add the payment sources to the current account inquiry
                for (ModelBean paymentSource: paymentSources) {
                	dtoAccountInquiry.addValue(paymentSource);
                }
                
                // Add DTOAccountInquiry to Response
                ModelBean oldDtoAccountInquiry = rs.getBeanById("DTOAccountInquiry", dtoAccountInquiry.getId());
                if( oldDtoAccountInquiry !=null ){
                	rs.deleteBeanById("DTOAccountInquiry", dtoAccountInquiry.getId());
                }
                rs.addValue(dtoAccountInquiry);
            }
            
            if( StringTools.isTrue(isExternal) ){
            
	            // delete the account & account inquiry beans before sending back the response
	            ModelBean[] accounts = rs.getBeans("Account");
	            for (ModelBean account : accounts) {
	                rs.deleteBeanById("Account", account.getId());
	            }
	            for (ModelBean accountInquiry : accountInquiries) {
	                rs.deleteBeanById("AccountInquiry", accountInquiry.getId());            	
	            }
            }
            
            // Return the Response
            return rs;
        }
        catch( Exception e ) {
            throw new ServiceHandlerException(e);
        }
    }
    
    /** Combine the two list of payment sources, one from the current term and the other from the new term
     * @param data The data connection
     * @param paymentSources The current term payment sources
     * @param otherPaymentSources The prior term payment sources
     * @return The combined list of payment sources
     * @throws Exception when an error occurs
     */
    public ModelBean[] combinePaymentSources(ModelBean[] paymentSources, ModelBean[] otherPaymentSources) throws Exception {
        ElectronicPayment electronicPayment = new ElectronicPayment();
    	ArrayList<ModelBean> combinedPaymentSources = new ArrayList<ModelBean>();
    	HashMap<String, String> keyMap = new HashMap<String, String>();
    	for (ModelBean paymentSource : paymentSources) {
    		String key = electronicPayment.getReceiptPaymentSourceKey(paymentSource);
    		if (!keyMap.containsKey(key)) {
    			keyMap.put(key, key);
    			combinedPaymentSources.add(paymentSource);
    		}
    	}
    	for (ModelBean paymentSource : otherPaymentSources) {
    		String key = electronicPayment.getReceiptPaymentSourceKey(paymentSource);
    		if (!keyMap.containsKey(key)) {
    			keyMap.put(key, key);
    			combinedPaymentSources.add(paymentSource);
    		}
    	}
    	return combinedPaymentSources.toArray(new ModelBean[combinedPaymentSources.size()]);
    }
}

