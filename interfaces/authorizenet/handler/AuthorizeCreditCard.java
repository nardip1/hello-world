/*
 * AuthorizeCreditCard.java
 *
 */

package com.cbic.ar.interfaces.authorizenet.handler;

import java.util.ArrayList;

import net.inov.biz.server.IBIZException;
import net.inov.biz.server.ServiceHandlerException;
import net.inov.mda.MDAOption;
import net.inov.tec.beans.ModelBean;
import net.inov.tec.data.JDBCData;
import net.inov.tec.date.StringDate;

import com.iscs.ar.AR;
import com.iscs.ar.HPAccount;
import com.iscs.common.shared.SystemData;
import com.iscs.common.tech.log.Log;
import com.iscs.common.utility.SessionInfo;
import com.iscs.common.utility.StringTools;
import com.iscs.common.utility.error.ErrorTools;
import com.iscs.insurance.customer.Customer;
import com.iscs.interfaces.authorizenet.AuthorizeNet;
import com.iscs.interfaces.electronicpayment.ElectronicPaymentException;
import com.iscs.workflow.Task;

/** Authorize the credit card for the requested dollar amount
 *
 * @author moniquef
 */
public class AuthorizeCreditCard extends com.iscs.ar.interfaces.authorizenet.handler.AuthorizeCreditCard {
    
    /** Creates a new instance of AuthorizeCreditCard
     * @throws Exception never
     */
    public AuthorizeCreditCard() throws Exception {
    }
    
    /** Processes a generic service request.
     * @return the current response bean
     * @throws IBIZException when an error requiring user attention occurs
     * @throws ServiceHandlerException when a critical error occurs
     */
    public ModelBean process() throws IBIZException, ServiceHandlerException {
        try {
            // Log a greeting
            Log.debug("Processing AuthorizeCreditCard..."); 
            ModelBean rs = getHandlerData().getResponse();	
        	ModelBean rq = getHandlerData().getRequest();

            JDBCData data = getHandlerData().getConnection();            
            
            
            boolean postAndReverseOnDecline = false;
            // Get Additional Parameters
            ModelBean ap = rq.getBean("RequestParams").getBean("AdditionalParams");
            ModelBean postAndReverseOnDeclineBean = ap.getBean("Param", "Name", "PostAndReverseOnDecline");
            if(postAndReverseOnDeclineBean != null && StringTools.isTrue(postAndReverseOnDeclineBean.gets("Value"))){
            	postAndReverseOnDecline = true;
            }
            
            // Get Batch Receipt
            ModelBean batchReceipt = rs.getBean("BatchReceipt");
            
            // Get Payment Type
            ModelBean[] arReceiptArray = batchReceipt.findBeansByFieldValue("ARReceipt", "StatusCd", "New", false);

            // Obtain the statement account and first policy account to determine which task to send on credit card errors
            ModelBean statementAccount = null;
            String accountNumber = "";
            for( ModelBean arReceipt : arReceiptArray ) {
            	String accountSourceRef = arReceipt.gets("SourceRef");
            	if(accountSourceRef != null && !accountSourceRef.equals("")){
            		ModelBean account = data.selectMiniBean("AccountMini", accountSourceRef);
            		if(account != null) {
            			if( !HPAccount.getARObject(account).isFeeAccount(account) ) {
            				if (accountNumber.isEmpty()) {
                	        	accountNumber = arReceipt.gets("AccountNumber");
            				}
            			} else {
            				statementAccount = account;
            			}
            		}
            	}	
            }
            if (!accountNumber.isEmpty()) {
            	statementAccount = null;
            }
            
            // Get First Receipt (Only Statement Account will have more than one receipt)
            String paymentTypeCd = arReceiptArray[0].gets("TypeCd");
            
            // If Credit Card and Authorize.Net Interface is Enabled
            if( paymentTypeCd.equals("Credit Card") && AuthorizeNet.hasInterface() ) {
            	for( ModelBean arReceipt : arReceiptArray ) {
	                
            		// Get Electronic Payment Source 
	            	ModelBean electronicPaymentSource = arReceipt.getBean("ElectronicPaymentSource");	            	 
	            	String preAuthorizationNumber = electronicPaymentSource.gets("CreditCardAuthorizationCd");
	            	
	            	// if already authorized, do not request another authorization
	            	if( !preAuthorizationNumber.equals("") ){
	            		return rs;
	            	}
            	}
            }
            
            // If Credit Card and Authorize.Net Interface is Enabled
            if( paymentTypeCd.equals("Credit Card") && AuthorizeNet.hasInterface() ) {
            	
            	boolean firstReceipt = true;
            	String transactionId = "";
                String authorizationNumber = "";
                String creditCardTypeCd = "";
            	for( ModelBean arReceipt : arReceiptArray ) {
            	                
            		// Get Electronic Payment Source 
	            	ModelBean electronicPaymentSource = arReceipt.getBean("ElectronicPaymentSource");            	 	            	
	            	
            		// For Statement Accounts only validate and authorize one charge for the full check amount 
	            	if( firstReceipt) {
            			
		            	// Get Payment Amount
		            	String paymentAmt = arReceipt.gets("CheckAmt");
		            	
		            	// Validate Required Fields
		            	ModelBean errors = new ModelBean("Errors");
		            	AuthorizeNet authorizeNet = new AuthorizeNet();
		            	authorizeNet.validatePaymentRequest(electronicPaymentSource, paymentAmt, errors);
		            	
		            	// Loop Through Validation Errors and Add Them to the Response
		            	ModelBean[] errorArray = errors.getBeans("Error");
		                for( ModelBean error :  errorArray ) {
		                    addErrorMsg(error.gets("Name"), error.gets("Msg"), error.gets("Type"));
		                }
		            	
		                if( errorArray.length == 0 ) {
		                	
		                	try {
				            	// Authorize Check Amount
			                	authorizeNet.submitAuthorizationRequest(data, electronicPaymentSource, paymentAmt, errors);
			                	transactionId = electronicPaymentSource.gets("TransactionId");
			                	authorizationNumber = electronicPaymentSource.gets("CreditCardAuthorizationCd");
			                	creditCardTypeCd = electronicPaymentSource.gets("CreditCardTypeCd");
		                	
			                	// Loop Through Validation Errors and Add Them to the Response
			                	errorArray = errors.getBeans("Error");
			                    for( ModelBean error :  errorArray ) {
			                    	// Check for the Credit card Errors with corresponding error codes( ie non CreditCardProcessingError) and convert them to CreditCardDeclinedError Warning
			                    	// CreditCardDeclinedError Warning is translated to PostAndReverseInsteadOfCharge Additional Param by ChargeCreditCard.java Handler
			                    	if(postAndReverseOnDecline && !error.gets("Name").equals("CreditCardProcessingError")){
			                    	    ModelBean param = ap.findBeanByFieldValue("Param","Name", "BookDt");
			                    	    StringDate bookDt = null;
			                    	    if(param != null){
			                    	    	bookDt = param.getDate("Value");
			                    	    }else{
		                    				bookDt = new StringDate(SystemData.getValue(data, "BookDt"));								
			                    	    }
			                			SessionInfo info = new SessionInfo(rq.getBean("RequestParams"), rs.getBean("ResponseParams"), bookDt ); 	
			                    		handleErrors(data, error, accountNumber, statementAccount, info);
			                    	}else{
			                    		addErrorMsg(error.gets("Name"), error.gets("Msg"), error.gets("Type"));
			                    	}
			                    }
			                    
		                	} catch( ElectronicPaymentException e ) {
				            	addErrorMsg("General", e.getMessage(), ErrorTools.GENERIC_BUSINESS_ERROR, ErrorTools.SEVERITY_ERROR);
				            	 
			            	} 
		                }
		                
		                firstReceipt = false;
            		}
            		
	            	// Update All Payments with the authorization values
            		electronicPaymentSource.setValue("CreditCardAuthorizationCd", authorizationNumber);
	            	electronicPaymentSource.setValue("TransactionId", transactionId);
	            	electronicPaymentSource.setValue("CreditCardTypeCd", creditCardTypeCd);
	            }
            	
            	data.saveModelBean(batchReceipt);
            }                
            
            // If Response has Errors, Throw IBIZException
            if( hasErrors() )
                throw new IBIZException();
            
            return rs;
        } catch( IBIZException e ) {
            throw e;
        } catch( Exception e ) {
            throw new ServiceHandlerException(e);
        }
    }

	/**Error Handling based on the Response Reason Code from Authorize.net Processor
	 * Refers to cim-exception-codes.xml for actions
	 * @param data
	 * @param error Error Object from Authorization process
	 * @param accountNumber Policy Number
	 * @param statementAccount Statement Account Account
	 * @param info Session infor with userid, bookdate etc.
	 * @throws Exception
	 */
	protected void handleErrors(JDBCData data, ModelBean error, String accountNumber, ModelBean statementAccount, SessionInfo info) throws Exception {
		MDAOption[] options = AuthorizeNet.getOptions(error.gets("Name"));
		for(int i=0; i<options.length; i++){				
			if (options[i].getValue().equalsIgnoreCase("Reverse")){ 			    	                   	                	            
				addErrorMsg("CreditCardDeclinedError", error.gets("Msg"),ErrorTools.GENERIC_BUSINESS_ERROR, ErrorTools.SEVERITY_WARN);
			} else if (options[i].getValue().equals("Task")) {
	            ArrayList<ModelBean> array = new ArrayList<ModelBean>();
				String taskNo = "";
				ModelBean container = null;
				if (statementAccount != null) {
					taskNo = "CustomerTask0010";
					container = new ModelBean("StatementAccount");
					data.selectModelBean(container, Integer.parseInt(statementAccount.gets("StatementAccountRef")));
					ModelBean customer = Customer.getCustomerBySystemId(data, Integer.parseInt(statementAccount.gets("CustomerRef")));
					array.add(customer);
				} else {
					taskNo = options[i].getLabel();
					String containerId = getPolicySystemId(data, accountNumber);
		            // Load the container for insertion into the task if needed
		            if (!containerId.equals("")) {
		                container = new ModelBean("Policy");               
		                data.selectModelBean(container, Integer.parseInt(containerId));
		            }
				}
				array.add(container);
				ModelBean[] beans = (ModelBean[]) array.toArray(new ModelBean[array.size()]);
				ModelBean task = Task.createTask(data,taskNo,beans,info.getUserId(),info.getBookDate(),null,"");	   
				task.setValue("text",error.gets("Msg"));
				Task.insertTask(data, task);	
			}
		}           
	}
}