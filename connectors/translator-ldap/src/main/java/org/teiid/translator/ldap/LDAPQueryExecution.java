/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

/**
 * 
 * Please see the user's guide for a full description of capabilties, etc.
 * 
 * Description/Assumptions:
 * 1. Table's name in source defines the base DN (or context) for the search.
 * Example: Table.NameInSource=ou=people,dc=gene,dc=com
 * [Optional] The table's name in source can also define a search scope. Append
 * a "?" character as a delimiter to the base DN, and add the search scope string. 
 * The following scopes are available:
 * SUBTREE_SCOPE
 * ONELEVEL_SCOPE
 * OBJECT_SCOPE
 * [Default] LDAPConnectorConstants.ldapDefaultSearchScope 
 * is the default scope used, if no scope is defined (currently, ONELEVEL_SCOPE).
 * 
 * 2. Column's name in source defines the LDAP attribute name.
 * [Default] If no name in source is defined, then we attempt to use the column name
 * as the LDAP attribute name.
 * 
 * 
 * TODO: Implement paged searches -- the LDAP server must support VirtualListViews.
 * TODO: Implement cancel.
 * TODO: Add Sun/Netscape implementation, AD/OpenLDAP implementation.
 * 
 * 
 * Note: 
 * Greater than is treated as >=
 * Less-than is treater as <=
 * If an LDAP entry has more than one entry for an attribute of interest (e.g. a select item), we only return the
 * first occurrance. The first occurance is not predictably the same each time, either, according to the LDAP spec.
 * If an attribute is not present, we return the empty string. Arguably, we could throw an exception.
 * 
 * Sun LDAP won't support Sort Orders for very large datasets. So, we've set the sorting to NONCRITICAL, which
 * allows Sun to ignore the sort order. This will result in the results to come back as unsorted, without any error.
 * 
 * Removed support for ORDER BY for two reasons:
 * 1: LDAP appears to have a limit to the number of records that 
 * can be server-side sorted. When the limit is reached, two things can happen:
 * a. If sortControl is set to CRITICAL, then the search fails.
 * b. If sortControl is NONCRITICAL, then the search returns, unsorted.
 * We'd like to support ORDER BY, no matter how large the size, so we turn it off,
 * and allow MetaMatrix to do it for us.
 * 2: Supporting ORDER BY appears to negatively effect the query plan
 * when cost analysis is used. We stop using dependent queries, and start
 * using inner joins.
 *
 */

package org.teiid.translator.ldap;

import java.io.IOException;
import java.lang.reflect.Array;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;
import javax.naming.ldap.SortControl;
import javax.naming.ldap.SortKey;

import org.teiid.core.types.ArrayImpl;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.Column;
import org.teiid.translator.ExecutionContext;
import org.teiid.translator.ResultSetExecution;
import org.teiid.translator.TranslatorException;
import org.teiid.translator.TypeFacility;



/** 
 * LDAPSyncQueryExecution is responsible for executing an LDAP search 
 * corresponding to a read-only "select" query.
 */
public class LDAPQueryExecution implements ResultSetExecution {

	static final String MULTIVALUED_CONCAT = "multivalued-concat"; //$NON-NLS-1$
	static final String delimiter = "?"; //$NON-NLS-1$
	
	private LDAPSearchDetails searchDetails;
	private LdapContext ldapCtx;
	private NamingEnumeration<?> searchEnumeration;
	private LDAPExecutionFactory executionFactory;
	private ExecutionContext executionContext;
	private SearchControls ctrls;
	private int resultCount;
	private Iterator<List<Object>> unwrapIterator;

	/** 
	 * Constructor
	 * @param executionMode the execution mode.
	 * @param ctx the execution context.
	 * @param logger the ConnectorLogger
	 * @param connection the LDAP Context
	 */
	public LDAPQueryExecution(LdapContext ldapContext,LDAPSearchDetails search, SearchControls searchControls, LDAPExecutionFactory factory,ExecutionContext context) {
		this.searchDetails = search;
		this.ldapCtx = ldapContext;
		this.ctrls = searchControls;
		this.executionFactory = factory;
		this.executionContext = context;
	}

	/** 
	 * method to execute the supplied query
	 * @param query the query object.
	 * @param maxBatchSize the max batch size.
	 */
	@Override
	public void execute() throws TranslatorException {
		String ctxName = this.searchDetails.getContextName();
		String filter = this.searchDetails.getContextFilter();
		if (ctxName == null || filter == null || this.ctrls == null) {
			throw new TranslatorException("Search context, filter, or controls were null. Cannot execute search."); //$NON-NLS-1$
		}
		setRequestControls(null);
		// Execute the search.
		executeSearch();
	}

	/** 
	 * Set the standard request controls
	 */
	private void setRequestControls(byte[] cookie) throws TranslatorException {
		List<Control> ctrl = new ArrayList<Control>();
		SortKey[] keys = searchDetails.getSortKeys();
		try {			
			if (keys != null) {
				ctrl.add(new SortControl(keys, Control.NONCRITICAL));
			}
			if (this.executionFactory.usePagination()) {
				ctrl.add(new PagedResultsControl(this.executionContext.getBatchSize(), cookie, Control.CRITICAL));
			}
			if (!ctrl.isEmpty()) {
				this.ldapCtx.setRequestControls(ctrl.toArray(new Control[ctrl.size()]));
				LogManager.logTrace(LogConstants.CTX_CONNECTOR, "Sort/pagination controls were created successfully."); //$NON-NLS-1$
			}
		} catch (NamingException ne) {
            final String msg = LDAPPlugin.Util.getString("LDAPSyncQueryExecution.setControlsError") +  //$NON-NLS-1$
            " : "+ne.getExplanation(); //$NON-NLS-1$
			throw new TranslatorException(ne, msg);
		} catch(IOException e) {
			throw new TranslatorException(e);
		}
	}

	/**
	 * Perform the LDAP search against the subcontext, using the filter and 
	 * search controls appropriate to the query and model metadata.
	 */
	private void executeSearch() throws TranslatorException {
		String filter = searchDetails.getContextFilter();
		try {
			searchEnumeration = this.ldapCtx.search("", filter, ctrls); //$NON-NLS-1$
		} catch (NamingException ne) {
            final String msg = LDAPPlugin.Util.getString("LDAPSyncQueryExecution.execSearchError"); //$NON-NLS-1$
			throw new TranslatorException(ne, msg + " : " + ne.getExplanation());  //$NON-NLS-1$ 
		} catch(Exception e) {
            final String msg = LDAPPlugin.Util.getString("LDAPSyncQueryExecution.execSearchError"); //$NON-NLS-1$
			throw new TranslatorException(e, msg); 
		}
	}

	// GHH 20080326 - attempt to implement cancel here.  First try to
	// close the searchEnumeration, then the search context.
	// We are very conservative when closing the enumeration
	// but less so when closing context, since it is safe to call close
	// on contexts multiple times
	@Override
	public void cancel() throws TranslatorException {
		close();
	}

	// GHH 20080326 - replaced existing implementation with the same
	// code as used by cancel method.  First try to
	// close the searchEnumeration, then the search context
	// We are very conservative when closing the enumeration
	// but less so when closing context, since it is safe to call close
	// on contexts multiple times
	@Override
	public void close() {
		if (searchEnumeration != null) {
			try {
				searchEnumeration.close();
			} catch (Exception e) { } // catch everything, because NamingEnumeration has undefined behavior if it previously hit an exception
		}
		if (ldapCtx != null) {
			try {
				ldapCtx.close();
			} catch (NamingException ne) {
	            LogManager.logWarning(LogConstants.CTX_CONNECTOR, LDAPPlugin.Util.gs(LDAPPlugin.Event.TEIID12003, ne.getExplanation()));
			}
		}
	}
	
	/**
	 * Fetch the next batch of data from the LDAP searchEnumerationr result.
	 * @return the next Batch of results.
	 */
	// GHH 20080326 - set all batches as last batch after an exception
	// is thrown calling a method on the enumeration.  Per Javadoc for
	// javax.naming.NamingEnumeration, enumeration is invalid after an
	// exception is thrown - by setting last batch indicator we prevent
	// it from being used again.
	// GHH 20080326 - also added return of explanation for generic
	// NamingException
	public List<?> next() throws TranslatorException {
		try {
			if (unwrapIterator != null) {
				if (unwrapIterator.hasNext()) {
					return unwrapIterator.next();
				}
				unwrapIterator = null;
			}
			// The search has been executed, so process up to one batch of
			// results.
			List<?> result = null;
			while (result == null && searchEnumeration != null && searchEnumeration.hasMore())
			{
				SearchResult searchResult = (SearchResult) searchEnumeration.next();
				result = getRow(searchResult);
			}
			
			if (result == null && this.executionFactory.usePagination()) {
			    byte[] cookie = null;
				Control[] controls = ldapCtx.getResponseControls();
		        if (controls != null) {
		        	for (int i = 0; i < controls.length; i++) {
		        		if (controls[i] instanceof PagedResultsResponseControl) {
		        			PagedResultsResponseControl prrc = (PagedResultsResponseControl)controls[i];
		                    cookie = prrc.getCookie();
		        		}
		        	}
		        }
		        
		        if (cookie == null) {
		        	return null;
		        }
	
		        setRequestControls(cookie);
		        executeSearch();
		        return next();
			}

			if (result != null) {
				resultCount++;
			}
			return result;
		} catch (SizeLimitExceededException e) {
			if (resultCount != searchDetails.getCountLimit()) {
				String msg = LDAPPlugin.Util.gs(LDAPPlugin.Event.TEIID12008);
				TranslatorException te = new TranslatorException(e, msg);
				if (executionFactory.isExceptionOnSizeLimitExceeded()) {
					throw te;
				}
				this.executionContext.addWarning(te);
				LogManager.logWarning(LogConstants.CTX_CONNECTOR, e, msg); 
			}
			return null; // GHH 20080326 - if size limit exceeded don't try to read more results
		} catch (NamingException ne) {
			throw new TranslatorException(ne, LDAPPlugin.Util.gs("ldap_error")); //$NON-NLS-1$
		}
	}

	/**
	 * Create a row using the searchResult and add it to the supplied batch.
	 * @param batch the supplied batch
	 * @param result the search result
	 */
	// GHH 20080326 - added fetching of DN of result, for directories that
	// do not include it as an attribute
	private List<?> getRow(SearchResult result) throws TranslatorException {
		Attributes attrs = result.getAttributes();
		ArrayList<Column> attributeList = searchDetails.getElementList();
		final List<Object> row = new ArrayList<Object>(attributeList.size());
		
		int unwrapPos = -1;
		for (int i = 0; i < attributeList.size(); i++) {
			Column col = attributeList.get(i);
			Object val = getValue(col, result, attrs);  // GHH 20080326 - added resultDN parameter to call
			row.add(val);
			if (executionFactory.isUnwrapMultiValued() && !col.getJavaType().isArray() && val != null && val.getClass() == ArrayImpl.class) {
				if (unwrapPos > -1) {
					throw new TranslatorException(LDAPPlugin.Util.gs(LDAPPlugin.Event.TEIID12014, col, attributeList.get(unwrapPos)));
				}
				unwrapPos = i;
			}
		}
		
		if (unwrapPos > -1) {
			final Object[] val = ((ArrayImpl) row.get(unwrapPos)).getValues();
			final int pos = unwrapPos;
			unwrapIterator = new Iterator<List<Object>>() {
				int i = 0;
				@Override
				public boolean hasNext() {
					return i < val.length;
				}
				@Override
				public List<Object> next() {
					List<Object> newRow = new ArrayList<Object>(row);
					newRow.set(pos, val[i++]);
					return newRow;
				}
				@Override
				public void remove() {
					
				}
			};
			if (unwrapIterator.hasNext()) {
				return unwrapIterator.next();
			}
		}
		return row;
	}

	/**
	 * Add Result to Row
	 * @param modelElement the model element
	 * @param attrs the attributes
	 * @param row the row
	 */
	// GHH 20080326 - added resultDistinguishedName to method signature.  If
	// there is an element in the model named "DN" and there is no attribute
	// with this name in the search result, we return this new parameter
	// value for that column in the result
	// GHH 20080326 - added handling of ClassCastException when non-string
	// attribute is returned
	private Object getValue(Column modelElement, SearchResult result, Attributes attrs) throws TranslatorException {

		String strResult = null;
		String modelAttrName = modelElement.getSourceName();
		Class<?> modelAttrClass = modelElement.getJavaType();
		
		String multivalAttr = modelElement.getDefaultValue();
		
		if(modelAttrName == null) {
            final String msg = LDAPPlugin.Util.getString("LDAPSyncQueryExecution.nullAttrError"); //$NON-NLS-1$
			throw new TranslatorException(msg); 
		}

		Attribute resultAttr = attrs.get(modelAttrName);
		
		// If the attribute is not present, we return NULL.
		if(resultAttr == null) {
			// GHH 20080326 - return DN from input parameter
			// if DN attribute is not present in search result
			if (modelAttrName.toUpperCase().equals("DN")) {  //$NON-NLS-1$
				return result.getNameInNamespace();
			}
			return null;
		}
		Object objResult = null;
		try {
			if(TypeFacility.RUNTIME_TYPES.STRING.equals(modelAttrClass) && MULTIVALUED_CONCAT.equalsIgnoreCase(multivalAttr)) { 
				// mpw 5/09
				// Order the multi-valued attrs alphabetically before creating a single string, 
				// using the delimiter to separate each token
				ArrayList<String> multivalList = new ArrayList<String>();
				NamingEnumeration<?> attrNE = resultAttr.getAll();
				int length = 0;
				while(attrNE.hasMore()) {
					String val = (String)attrNE.next();
					multivalList.add(val);
					length += ((val==null?0:val.length()) + 1);
				}
				Collections.sort(multivalList);
	
				StringBuilder multivalSB = new StringBuilder(length);
				Iterator<String> itr = multivalList.iterator();
				while(itr.hasNext()) {
					multivalSB.append(itr.next());
					if (itr.hasNext()) {
						multivalSB.append(delimiter);
					}
				}
				return multivalSB.toString();
			}
			if (modelAttrClass.isArray()) {
				return getArray(modelAttrClass.getComponentType(), resultAttr);
			}
			if (executionFactory.isUnwrapMultiValued() && resultAttr.size() > 1) {
				return getArray(modelAttrClass, resultAttr);
			}
			
			//just a single value
			objResult = resultAttr.get();
		} catch (NamingException ne) {
            final String msg = LDAPPlugin.Util.gs(LDAPPlugin.Event.TEIID12004, modelAttrName) +" : "+ne.getExplanation(); //$NON-NLS-1$m
            LogManager.logWarning(LogConstants.CTX_CONNECTOR, msg);
			throw new TranslatorException(msg);
		}

		// GHH 20080326 - if attribute is not a string or empty, just
		// return null.
		// TODO - allow return of non-strings (always byte[]) as
		// MM object (or blob).  Perhaps also add directory-specific logic
		// to deserialize byte[] attributes into Java objects
		// when appropriate
		if (objResult instanceof String) {
			strResult = (String)objResult;
			// MPW - 3.9.07 - Also return NULL when attribute is unset or empty string.
			// There is no way to differentiate between being unset and being the empty string.
			if(strResult.equals("")) {  //$NON-NLS-1$
				strResult = null;
			}
		}

		// MPW: 3-11-07: Added support for java.lang.Integer conversion.
		if(TypeFacility.RUNTIME_TYPES.TIMESTAMP.equals(modelAttrClass)) {
			String timestampFormat = modelElement.getFormat();
			if(timestampFormat == null) {
				timestampFormat = LDAPConnectorConstants.ldapTimestampFormat;
			}
			SimpleDateFormat dateFormat = new SimpleDateFormat(timestampFormat);
			try {
				if(strResult != null) {
					Date dateResult = dateFormat.parse(strResult);
					Timestamp tsResult = new Timestamp(dateResult.getTime());
					return tsResult;
				}
				return null;
			} catch(ParseException pe) {
				throw new TranslatorException(pe, LDAPPlugin.Util.getString("LDAPSyncQueryExecution.timestampParseFailed", modelAttrName)); //$NON-NLS-1$
			}		
			
			//	TODO: Extend support for more types in the future.
			// Specifically, add support for byte arrays, since that's actually supported
			// in the underlying data source.
		}
		return strResult; //the Teiid type conversion logic will handle refine from here if necessary
	}

	private ArrayImpl getArray(Class<?> componentType, Attribute resultAttr)
			throws NamingException {
		ArrayList<Object> multivalList = new ArrayList<Object>();
		NamingEnumeration<?> attrNE = resultAttr.getAll();
		int length = 0;
		while(attrNE.hasMore()) {
			multivalList.add(attrNE.next());
			length++;
		}
		Object[] values = (Object[]) Array.newInstance(componentType, length);
		ArrayImpl value = new ArrayImpl(multivalList.toArray(values));
		return value;
	}
	
	// for testing.
	LDAPSearchDetails getSearchDetails() {
		return this.searchDetails;
	}
}
