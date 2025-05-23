///////////////////////////////////////////////////////////////////////////////
//
// JTOpen (IBM Toolbox for Java - OSS version)
//
// Filename:  AS400.java
//
// The source code contained herein is licensed under the IBM Public License
// Version 1.0, which has been approved by the Open Source Initiative.
// Copyright (C) 1997-2024 International Business Machines Corporation and
// others.  All rights reserved.
//
///////////////////////////////////////////////////////////////////////////////

package com.ibm.as400.access;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.beans.PropertyVetoException;
import java.beans.VetoableChangeListener;
import java.beans.VetoableChangeSupport;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URL;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Vector;

import javax.net.ssl.SSLSocketFactory;

import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;

import com.ibm.as400.security.auth.ProfileTokenCredential;
import com.ibm.as400.security.auth.ProfileTokenEnhancedInfo;
import com.ibm.as400.security.auth.ProfileTokenProvider;

/**
 * Represents the authentication information and a set of connections to the IBM i host servers.
 * <p>
 * If running on IBM i or an older version of that operating system, the system name, user ID, and password do not need
 * to be supplied. These values default to the local system. For the system name, the keyword <code>localhost</code> can
 * be used to specify the local system. For the user ID and password, *CURRENT can be used.
 * <p>
 * If running on another operating system, the system name, user ID, and password need to be supplied. If not supplied,
 * the first 'open' request associated with this object will trigger a prompt to the workstation user. Subsequent opens
 * associated with the same object will not prompt the workstation user. Keywords <code>localhost</code> and *CURRENT
 * will not work when running on another operating system.
 * <p>
 * For example:
 * 
 * <pre>
      AS400 system = new AS400();
      system.connectService(AS400.DATAQUEUE);   // This causes a password prompt.
      ...
      system.connectService(AS400.FILE);        // This does not cause a prompt.
 * </pre>
 **/
public class AS400 implements Serializable, AutoCloseable
{
    private static final String CLASSNAME = "com.ibm.as400.access.AS400";
    
    static
    {
        if (Trace.traceOn_) Trace.logLoadPath(CLASSNAME);
    }

    static final long serialVersionUID = 4L;
    private static final boolean PASSWORD_TRACE = false;

    /** Constant indicating the File service. **/
    public static final int FILE = 0;
    /** Constant indicating the Print service.  **/
    public static final int PRINT = 1;
    /** Constant indicating the Command service. **/
    public static final int COMMAND = 2;
    /** Constant indicating the Dataqueue service. **/
    public static final int DATAQUEUE = 3;
    /** Constant indicating the Database service. **/
    public static final int DATABASE = 4;
    /** Constant indicating the Record Access service. **/
    public static final int RECORDACCESS = 5;
    /** Constant indicating the Central service. **/
    public static final int CENTRAL = 6;
    /** Constant indicating the Sign-on service. **/
    public static final int SIGNON = 7;
    /** Constant indicating the Host connection service. */
    public static final int HOSTCNN = 8;
    
    // Constants 8-15 reserved for SSL versions of the above services.

    /** Special value indicating the service port should be retrieved from the port mapper server. **/
    public static final int USE_PORT_MAPPER = -1;

    /** Constant indicating the authentication scheme is password. **/
    public static final int AUTHENTICATION_SCHEME_PASSWORD = 0;
    /** Constant indicating the authentication scheme is GSS token. **/
    public static final int AUTHENTICATION_SCHEME_GSS_TOKEN = 1;
    /** Constant indicating the authentication scheme is profile token. **/
    public static final int AUTHENTICATION_SCHEME_PROFILE_TOKEN = 2;
    /** Constant indicating the authentication scheme is identity token. **/
    public static final int AUTHENTICATION_SCHEME_IDENTITY_TOKEN = 3;
    /** Constant representing the DDM_EUSERIDPWD scheme - only applicable to Record Access service. */
    public static final int AUTHENTICATION_SCHEME_DDM_EUSERIDPWD = 4;
    
    /**
     Constant indicating that the JGSS framework must be used when no password or authentication token is set.  
     An object set to this option will not attempt to present a sign-on dialog or use the current user profile information.  
     A failure to retrieve the GSS token will result in an exception returned to the user.
     **/
    public static final int GSS_OPTION_MANDATORY = 0;
    /**
     Constant indicating that the JGSS framework will be attempted when no password or authentication token is set.  
     An object set to this option will attempt to retrieve a GSS token, if that attempt fails, the object will present 
     a sign-on dialog or use the current user profile information.  This option is the default.
     **/
    public static final int GSS_OPTION_FALLBACK = 1;
    /**
     Constant indicating that the JGSS framework will not be used when no password or authentication token is set.  
     An object set to this option will only present a sign-on dialog or use the current user profile information.
     **/
    public static final int GSS_OPTION_NONE = 2;
    
    /**
     Constant indicating that encryption should only be done on the connection between the client and the proxy server.
    **/
    public static final int CLIENT_TO_PROXY_SERVER = 1;

    /**
     Constant indicating that encryption should only be done on the connection between the proxy server and the system.
     **/
    public static final int PROXY_SERVER_TO_SERVER = 2;

    /** @deprecated Use CLIENT_TO_SERVER instead. **/
    public static final int CLINT_TO_SERVER = 3;

    /**
     Constant indicating that encryption should be done in both the connection between the client and the proxy server 
     and the connection between the proxy server and the system.
     **/
    public static final int CLIENT_TO_SERVER = 3;
   
    /** Indicate whether the cipher suites changed by the caller. We add this for iNav. */
    public static boolean changeCipherSuites = false;
    public static String[] newCipherSuites;


    // Determine if we are running on IBM i.
    static boolean onAS400 = false;
    // VRM from system property, if we are native.
    static ServerVersion nativeVRM = null;
    // The static default sign-on handler.
    static Class defaultSignonHandlerClass_ = ToolboxSignonHandler.class;
    static SignonHandler defaultSignonHandler_;
    // Default setting for guiAvailable property.
    private static boolean defaultGuiAvailable_ = true;
    // Default setting for mustAddLanguageLibrary property.
    private static boolean defaultMustAddLanguageLibrary_ = false;
    // Default setting for mustUseSockets property.
    private static boolean defaultMustUseSockets_ = false;
    // Default setting for mustUseNetSockets property.
    private static boolean defaultMustUseNetSockets_ = false;
    // Default setting for mustUseSuppliedProfile property.
    private static boolean defaultMustUseSuppliedProfile_ = false;
    // Default setting for threadUsed property.
    private static boolean defaultThreadUsed_ = true;
    
    boolean skipSignonServer_ = false;
    public String currentLib_ = "*CURUSR";
    public String librariesForThread_ = "*CURUSR";
    static
    {
        try
        {
            String s = System.getProperty("os.name");
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Detected os.name:", s);
            if (s != null && s.equalsIgnoreCase("OS/400"))
            {
                String version = System.getProperty("os.version");
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Detected os.version:", version);
                if (version != null)
                {
                    char[] versionChars = version.toCharArray();
                    if (versionChars.length == 6)
                    {
                        int vrm = ((versionChars[1] & 0x000F) << 16) + ((versionChars[3] & 0x000F) <<  8) + (versionChars[5] & 0x000F);
                        AS400.nativeVRM = new ServerVersion(vrm);
                    }
                }
                AS400.onAS400 = true;
            }
        } catch (SecurityException e) {
            Trace.log(Trace.WARNING, "Error retrieving os.name:", e);
        }

        // Get the "default sign-on handler" property.
        String propVal = SystemProperties.getProperty(SystemProperties.AS400_SIGNON_HANDLER);
        if (propVal != null) {
            try {
              defaultSignonHandlerClass_ = Class.forName(propVal);
            } catch (Exception e) {
              Trace.log(Trace.WARNING, "Error retrieving default sign-on handler (specified by property):", e);
              defaultSignonHandlerClass_ = ToolboxSignonHandler.class;
            }
        }

        // Get the "GUI available" property.
        propVal = SystemProperties.getProperty(SystemProperties.AS400_GUI_AVAILABLE);
        if (propVal != null) {
            try {
              defaultGuiAvailable_ = Boolean.valueOf(propVal).booleanValue();
            } catch (Exception e) {
              Trace.log(Trace.WARNING, "Error retrieving guiAvailable property value:", e);
            }
        }

        // Get the "must add language library" property.
        propVal = SystemProperties.getProperty(SystemProperties.AS400_MUST_ADD_LANGUAGE_LIBRARY);
        if (propVal != null) {
            try {
              defaultMustAddLanguageLibrary_ = Boolean.valueOf(propVal).booleanValue();
            } catch (Exception e) {
              Trace.log(Trace.WARNING, "Error retrieving mustAddLanguageLibrary property value:", e);
            }
        }

        // Get the "must use sockets" property.
        propVal = SystemProperties.getProperty(SystemProperties.AS400_MUST_USE_SOCKETS);
        if (propVal != null) {
            try {
              defaultMustUseSockets_ = Boolean.valueOf(propVal).booleanValue();
            } catch (Exception e) {
              Trace.log(Trace.WARNING, "Error retrieving mustUseSockets property value:", e);
            }
        }

        // Get the "must use net sockets" property.
        propVal = SystemProperties.getProperty(SystemProperties.AS400_MUST_USE_NET_SOCKETS);
        if (propVal != null) {
            try {
              defaultMustUseNetSockets_ = Boolean.valueOf(propVal).booleanValue();
            } catch (Exception e) {
              Trace.log(Trace.WARNING, "Error retrieving mustUseNetSockets property value:", e);
            }
        }

        // Get the "must use supplied profile" property.
        propVal = SystemProperties.getProperty(SystemProperties.AS400_MUST_USE_SUPPLIED_PROFILE);
        if (propVal != null) {
            try {
              defaultMustUseSuppliedProfile_ = Boolean.valueOf(propVal).booleanValue();
            } catch (Exception e) {
              Trace.log(Trace.WARNING, "Error retrieving mustUseSuppliedProfile property value:", e);
            }
        }

        // Get the "thread used" property.
        propVal = SystemProperties.getProperty(SystemProperties.AS400_THREAD_USED);
        if (propVal != null) {
            try {
              defaultThreadUsed_ = Boolean.valueOf(propVal).booleanValue();
            } catch (Exception e) {
              Trace.log(Trace.WARNING, "Error retrieving threadUsed property value:", e);
            }
        }
    }

    // System list:  elements are 3 element Object[]: systemName, userId, credential vault
    private static Vector systemList = new Vector();
    // Default users is a hash from systemName to userId.
    private static Hashtable defaultUsers = new Hashtable();
    // Number of days previous to password expiration to start to warn user.
    private static int expirationWarning = 7;
    // Whether to use system value QPWDEXPWRN to calculate days previous to password expiration.
    private boolean useSystemExpirationWarning_ = false;

    private static int alreadyCheckedForMultipleVersions_ = 0;
    
    
    // GSS Manager object, for Kerberos.
    private static GSSManager gssManager_ = null;

    // System name.
    private String systemName_ = "";
    // Flag indicating if system name refers to local system.
    private boolean systemNameLocal_ = false;
    // User ID.
    private String userId_ = "";

    // Credential vault used to store password, GSS token, identity token,
    // or profile token.  An AS400 object must always have its own copy of
    // a credential vault (i.e. there must be a 1-to-1 correlation between
    // AS400 objects and CredentialVault objects).  Sharing a credential vault
    // amongst two different AS400 objects is NEVER allowed.
    // If you need to share the credential in the vault with another AS400 object,
    // you must provide a copy of the credential vault.  This is achieved using
    // the clone() method provided by the CredentialVault class.
    private transient CredentialVault credVault_;  // never null after construction

    // Additional authentication factor
    private char[] additionalAuthenticationFactor_;
    
    // GSS Credential object, for Kerberos.  Type set to Object to prevent dependency on 1.4 JDK.
    private transient GSSCredential gssCredential_ = null;
    // GSS name string, for Kerberos.
    private String gssName_ = "";
    // How to use the GSS framework.
    int gssOption_ = GSS_OPTION_FALLBACK;

    // Proxy server system name.
    private transient String proxyServer_ = "";
    // Client side proxy connection information.
    private transient Object proxyClientConnection_ = null;  // Tolerate not having class ProxyClientConnection in the jar.

    // This controls the prompting.  If set to true, then prompting will occur during sign-on if needed.  If set to false, no prompting will occur and all security errors are returned as exceptions.
    private boolean guiAvailable_ = defaultGuiAvailable_;
    // Use the password cache.
    private boolean usePasswordCache_ = true;
    // Use the default user.
    private boolean useDefaultUser_ = true;
    // Show the checkboxes on the password dialog.
    private boolean showCheckboxes_ = true;
    // Detect/prevent recursion when interacting with sign-on handler.
    private boolean signingOn_ = false;

    // SSL options, null value indicates SSL is not to be used.  Options set in SecureAS400 subclass.
    SSLOptions useSSLConnection_ = null;
    
    // Flag that indicates if we must add the secondary language library to the library list.
    private boolean mustAddLanguageLibrary_ = defaultMustAddLanguageLibrary_;
    // Flag that indicates if we must use the host servers and no native optimizations.
    private boolean mustUseSockets_ = defaultMustUseSockets_;
    // Flag that indicates if we must use network sockets and not unix domain sockets.
    private boolean mustUseNetSockets_ = defaultMustUseNetSockets_;
    // Flag that indicates if we must not use the current profile.
    private boolean mustUseSuppliedProfile_ = defaultMustUseSuppliedProfile_;
    // Flag that indicates if we use threads in communication with the host servers.
    private boolean threadUsed_ = defaultThreadUsed_;
    // Locale object to use for determining NLV.
    private Locale locale_ = Locale.getDefault();
    // The NLV set or determined from the locale.
    private String nlv_ = ExecutionEnvironment.getNlv(Locale.getDefault());
    // The system's default time zone.
    private transient TimeZone timezone_;
    // Set of socket options to use when creating connections.
    private SocketProperties socketProperties_ = new SocketProperties();

    // No CCSID to start.
    transient int ccsid_ = 0;

    // List of connection event bean listeners.
    private transient Vector connectionListeners_ = null;  // Set on first add.
    // Inner class that connects connection events that occur in the ImplRemote to this class.
    private transient ConnectionListener dispatcher_ = null;  // Set on first add.
    // List of property change event bean listeners.
    transient PropertyChangeSupport propertyChangeListeners_ = null;  // Set on first add.
    // List of vetoable change event bean listeners.
    transient VetoableChangeSupport vetoableChangeListeners_ = null;  // Set on first add.

    // Flag for when object state is allowed to change.
    transient boolean propertiesFrozen_ = false;

    // Implementation object.
    private transient AS400Impl impl_ = null;

    // This object is created by the initial sign-on process.  It contains the information from
    // the retrieve sign-on information flow with the sign-on server.
    private transient SignonInfo signonInfo_ = null;

    // The IASP name used for the RECORDACCESS service.
    private String ddmRDB_ = null;

    // The sign-on handler for this object's instance.
    private transient SignonHandler signonHandler_ = null;
    private transient boolean handlerCanceled_ = false;

    /* forcePrompt_ is a flag that tells AS400 to force prompt by displaying login dialog (actually the sign-on handler) prior to even trying to authenticate.
    This is useful in cases where an application sends in incorrect dummy id/password and expects Toolbox to display the logon dialog.
    In JDBC, we do some pre-validation of id/password.  So JDBC may flag the id/password as invalid and then need
    to let AS400 know that it just needs to display the logon dialog. */
    private boolean forcePrompt_ = false;
    private int validateSignonTimeOut_ = 0;

    /**
     * Constructs an AS400 object.
     * <p>
     * If running on IBM i, the target is the local system. This has the same effect as using <code>localhost</code> for
     * the system name, *CURRENT for the user ID, and *CURRENT for the password.
     * <p>
     * If running on another operating system, a sign-on prompt may be displayed. The user is then able to specify the
     * system name, user ID, and password.
     **/
    public AS400()
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object.");
        construct();
        systemNameLocal_ = resolveSystemNameLocal("");
        proxyServer_ = resolveProxyServer(proxyServer_);

        // Default to password authentication
        credVault_ = new PasswordVault();
    }

    /**
     * Constructs an AS400 object. It uses the specified system name.
     * <p>
     * If running on IBM i to another system or to itself, the user ID and password of the current job are used.
     * <p>
     * If running on another operating system, the user may be prompted for the user ID and password if a default user
     * has not been established for this system name.
     * 
     * @param systemName The name of the IBM i system. Use <code>localhost</code> to access data locally.
     **/
    public AS400(String systemName)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object, system name: '" + systemName + "'");
        if (systemName == null)
            throw new NullPointerException("systemName");

        construct();
        systemName_ = systemName;
        systemNameLocal_ = resolveSystemNameLocal(systemName);
        proxyServer_ = resolveProxyServer(proxyServer_);

        // Default to password authentication
        credVault_ = new PasswordVault();
    }

    /**
     * Constructs an AS400 object. It uses the specified system name and user ID. If the sign-on prompt is displayed,
     * the user is able to specify the password. Note that the user ID may be overridden.
     * 
     * @param systemName The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId     The user profile name to use to authenticate to the system. If running on IBM i, *CURRENT may
     *                   be used to specify the current user ID.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     **/
    public AS400(String systemName, String userId)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object, system name: '" + systemName + "' user ID: '" + userId + "'");
        if (systemName == null)
            throw new NullPointerException("systemName");

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        construct();
        systemName_ = systemName;
        systemNameLocal_ = resolveSystemNameLocal(systemName);

        if (isTurkish()) {
            userId = userId.toUpperCase(Locale.ENGLISH);
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }

        userId_ = userId.toUpperCase();
        proxyServer_ = resolveProxyServer(proxyServer_);

        // Default to password authentication
        credVault_ = new PasswordVault();
    }

    /**
     * Constructs an AS400 object. It uses the specified system name and profile token.
     * 
     * @param systemName   The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param profileToken The profile token to use to authenticate to the system.
     **/
    public AS400(String systemName, ProfileTokenCredential profileToken)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object with profile token, system name: '" + systemName + "'");
        if (PASSWORD_TRACE) Trace.log(Trace.DIAGNOSTIC, "profile token: " + profileToken);

        if (profileToken == null)
            throw new NullPointerException("profileToken");

        constructWithProfileToken(systemName, new ProfileTokenVault(profileToken));
    }

    /**
     * Constructs an AS400 object. The specified ProfileTokenProvider is used. The token refresh threshold is determined
     * by the ProfileTokenProvider.
     * 
     * @param systemName    The name of the IBM i system.
     * @param tokenProvider The provider to use when a new profile token needs to be generated.
     * @see #AS400(String,ProfileTokenProvider,int)
     */
    public AS400(String systemName, ProfileTokenProvider tokenProvider) {
        this(systemName, tokenProvider, null);
    }

    /**
     * Constructs an AS400 object. The specified ProfileTokenProvider is used.
     * 
     * @param systemName       The name of the IBM i system.
     * @param tokenProvider    The provider to use when a new profile token needs to be generated.
     * @param refreshThreshold The refresh threshold, in seconds, for the profile token. Used by the vault to manage the
     *                         currency of the profile token to help ensure it remains current for an indefinite period
     *                         of time.
     * @see #AS400(String,ProfileTokenProvider)
     */
    public AS400(String systemName, ProfileTokenProvider tokenProvider, int refreshThreshold) {
        this(systemName, tokenProvider, Integer.valueOf(refreshThreshold));
    }

    private AS400(String systemName, ProfileTokenProvider tokenProvider, Integer refreshThreshold)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object with a profile token provider, system name: '" + systemName + "'");

        if (tokenProvider == null)
            throw new NullPointerException("tokenProvider");

        if (PASSWORD_TRACE) Trace.log(Trace.DIAGNOSTIC, "profile token provider:", tokenProvider.getClass().getName());

        constructWithProfileToken(systemName, (refreshThreshold != null) 
                                                   ? new ManagedProfileTokenVault(tokenProvider, refreshThreshold.intValue())
                                                   : new ManagedProfileTokenVault(tokenProvider));
    }

    /**
     * Common code for constructing an AS400 object that uses profile token authentication
     */
    private void constructWithProfileToken(String systemName, ProfileTokenVault credVault)
    {
        if (systemName == null)
            throw new NullPointerException("systemName");

        construct();
        systemName_ = systemName;
        systemNameLocal_ = resolveSystemNameLocal(systemName);

        // Assumption: The caller of this method has ensured that the credential
        // vault has been created and initialized correctly.
        credVault_ = credVault;
        proxyServer_ = resolveProxyServer(proxyServer_);
    }

    /**
     * Constructs an AS400 object. It uses the specified system name, user ID, and password. No sign-on prompt is
     * displayed unless the sign-on fails.
     * 
     * @param systemName The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId     The user profile name to use to authenticate to the system. If running on IBM i, *CURRENT may
     *                   be used to specify the current user ID.
     * @param password   The user profile password to use to authenticate to the system. If running on IBM i, CURRENT
     *                   may be used to specify the current user ID.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     * @deprecated Use AS400(String systemName, String userId, char[] password) instead
     **/
    @Deprecated
    public AS400(String systemName, String userId, String password)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object with password(String), system name: '" + systemName + "' user ID: '" + userId + "'");
        if (PASSWORD_TRACE) Trace.log(Trace.DIAGNOSTIC, "password: '" + password + "'");
        if (systemName == null)
            throw new NullPointerException("systemName");

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        char[] passwordChars = (password == null) ? null : password.toCharArray();
        
        try 
        {
            checkPasswordNullAndLength(passwordChars, "password");
            construct();
            systemName_ = systemName;
            systemNameLocal_ = resolveSystemNameLocal(systemName);
 
            if (isTurkish()) {
                userId = userId.toUpperCase(Locale.ENGLISH);
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
            }

            userId_ = userId.toUpperCase();

            credVault_ = new PasswordVault(passwordChars);
        }
        finally {
            PasswordVault.clearArray(passwordChars);
        }
        
        proxyServer_ = resolveProxyServer(proxyServer_);
    }

    /**
     * Constructs an AS400 object. It uses the specified system name, user ID, password, and additional authentication
     * factor. No sign-on prompt is displayed unless the sign-on fails.
     * 
     * @param systemName           The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId               The user profile name to use to authenticate to the system. If running on IBM i,
     *                             *CURRENT may be used to specify the current user ID.
     * @param password             The user profile password to use to authenticate to the system.
     * @param additionalAuthFactor Additional authentication factor (or null if not providing one). The caller is
     *                             responsible for clearing the password array to keep the password from residing in
     *                             memory.
     * @throws AS400SecurityException If a security or authority error occurs.
     * @throws IOException            If an error occurs while communicating with the system.
     **/
    public AS400(String systemName, String userId, char[] password, char[] additionalAuthFactor) throws AS400SecurityException, IOException
    {
        this(systemName, userId, password);
        setAdditionalAuthenticationFactor(additionalAuthFactor);
    }

    /**
     * Constructs an AS400 object. It uses the specified system name, user ID, and password. No sign-on prompt is
     * displayed unless the sign-on fails.
     * 
     * @param systemName The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId     The user profile name to use to authenticate to the system. If running on IBM i, *CURRENT may
     *                   be used to specify the current user ID.
     * @param password   The user profile password to use to authenticate to the system. The caller is responsible for
     *                   clearing the password array to keep the password from residing in memory.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     **/
    public AS400(String systemName, String userId, char[] password)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object with password[], system name: '" + systemName + "' user ID: '" + userId + "'");
        if (PASSWORD_TRACE) Trace.log(Trace.DIAGNOSTIC, "password: '" + new String(password) + "'");
        if (systemName == null)
            throw new NullPointerException("systemName");

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        checkPasswordNullAndLength(password, "password");
        construct();
        systemName_ = systemName;
        systemNameLocal_ = resolveSystemNameLocal(systemName);
 
        if (isTurkish()) {
          userId = userId.toUpperCase(Locale.ENGLISH);
          if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }
 
        userId_ = userId.toUpperCase();
        credVault_ = new PasswordVault(password);
        proxyServer_ = resolveProxyServer(proxyServer_);
    }
    
    private static final String[] USRLIBL_SINGLE_VALUE = new String[]
    {
        "*CURSYSBAS",
        "*CURUSR",
        "*SYSVAL",
        "*NONE"
    };
    public String aspName="";

    /**
     * Set ASP group for the AS400 connection to the Remote Command Host server. It calls SETASPGRP command to change
     * the asp setting for corresponding CommandCall, ProgramCall and ServiceProgramCall in the same connection. Current
     * library default is *CURUSR. Libraries for current thread default is *CURUSR. If an ASP group had already been
     * set, it will remove the old ASP group and set the specified ASP group for the current thread. Once the specified
     * ASP group has been set, all libraries in the independent ASPs in the ASP group are accessible and objects in
     * those libraries can be referenced using regular library-qualified object name syntax.
     * 
     * @param IASPGroup asp group name
     * @throws AS400SecurityException          If a security or authority error occurs.
     * @throws ErrorCompletingRequestException If an error occurs before the request is completed.
     * @throws IOException                     If an error occurs while communicating with the system.
     * @throws InterruptedException            If this thread is interrupted.
     * @throws PropertyVetoException           If the recipient wishes the property change to be rolled back.
     */
    public void setIASPGroup(String IASPGroup) throws AS400SecurityException, ErrorCompletingRequestException, IOException, InterruptedException, PropertyVetoException
    {
      this.currentLib_ = "*CURUSR";
      this.librariesForThread_ = "*CURUSR";
      String SetASPGrp = "SETASPGRP ASPGRP("+ IASPGroup + ") CURLIB(*CURUSR) USRLIBL(*CURUSR)"; //@P2C Default value *CURSYSBAS will override the user profile/jobd set libs.
      Trace.log(Trace.DIAGNOSTIC,"AS400 Call command of setaspgrp "+SetASPGrp);
      CommandCall commandCall = new CommandCall(this);
      if (commandCall.run(SetASPGrp) != true) {
        Trace.log(Trace.ERROR, this,"Command SETASPGRP Failed with iasp "+IASPGroup);
      } 
      else
        aspName = IASPGroup;
    }

    /**
     * Set ASP group for the AS400 connection to the Remote Command Host server. It calls SETASPGRP command to change
     * the asp setting for corresponding CommandCall, ProgramCall and ServiceProgramCall in the same connection.
     * Libraries for current thread default is *CURUSR. If an ASP group had already been set, it will remove the old ASP
     * group and set the specified ASP group for the current thread. Once the specified ASP group has been set, all
     * libraries in the independent ASPs in the ASP group are accessible and objects in those libraries can be
     * referenced using regular library-qualified object name syntax.
     * 
     * @param IASPGroup  asp group name
     * @param currentLib Current library which can be *CURSYSBAS, *CURUSR, *CRTDFT, name. If null or "" is set, default
     *                   value *CURUSR is used.
     * @throws AS400SecurityException          If a security or authority error occurs.
     * @throws ErrorCompletingRequestException If an error occurs before the request is completed.
     * @exception ExtendedIllegalArgumentException If currentLib length is not valid.
     * @throws IOException                     If an error occurs while communicating with the system.
     * @throws InterruptedException            If this thread is interrupted.
     * @throws PropertyVetoException           If the recipient wishes the property change to be rolled back.
     */
    public void setIASPGroup(String IASPGroup, String currentLib) throws AS400SecurityException, ErrorCompletingRequestException, IOException, InterruptedException, PropertyVetoException
    {
      if(currentLib==null || currentLib.length()==0)
        currentLib = "*CURUSR";
      else if (currentLib.length() > 10)
      {
          Trace.log(Trace.ERROR, "Length of parameter 'currentLib' is not valid: '" + currentLib + "'");
          throw new ExtendedIllegalArgumentException("setIASPGroup currentLib (" + currentLib + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
      }
      
      this.currentLib_ = currentLib;
      this.librariesForThread_ = "*CURUSR";
      String SetASPGrp = "SETASPGRP ASPGRP("+ IASPGroup + ") CURLIB("+currentLib+") USRLIBL(*CURUSR)"; //@P2C Default value *CURSYSBAS will override the user profile/jobd set libs.
      Trace.log(Trace.DIAGNOSTIC,"Call command of setaspgrp "+SetASPGrp);
      CommandCall commandCall = new CommandCall(this);
      if (commandCall.run(SetASPGrp) != true) {
        Trace.log(Trace.ERROR, this,"Command SETASPGRP Failed with iasp "+IASPGroup);
      } else
        aspName = IASPGroup;
    } 
    
    /**
     * Set ASP group for the AS400 connection to the Remote Command Host server. It calls SETASPGRP command to change
     * the asp setting for corresponding CommandCall, ProgramCall and ServiceProgramCall in the same connection. If an
     * ASP group had already been set, it will remove the old ASP group and set the specified ASP group for the current
     * thread. Once the specified ASP group has been set, all libraries in the independent ASPs in the ASP group are
     * accessible and objects in those libraries can be referenced using regular library-qualified object name syntax.
     * 
     * @param IASPGroup          asp group name
     * @param currentLib         Current library which can be *CURSYSBAS, *CURUSR, *CRTDFT, name. If null or "" is set,
     *                           default value *CURUSR is used.
     * @param librariesForThread Libraries for current thread with single value. If null or "" is set, default value
     *                           *CURUSR is used.
     * @throws AS400SecurityException          If a security or authority error occurs.
     * @throws ErrorCompletingRequestException If an error occurs before the request is completed.
     * @exception ExtendedIllegalArgumentException If currentLib or librariesForThread lengths are not valid.
     * @throws IOException                     If an error occurs while communicating with the system.
     * @throws InterruptedException            If this thread is interrupted.
     * @throws PropertyVetoException           If the recipient wishes the property change to be rolled back.
     */
    public void setIASPGroup(String IASPGroup, String currentLib, String librariesForThread) throws AS400SecurityException, ErrorCompletingRequestException, IOException, InterruptedException, PropertyVetoException
    {
      if(currentLib==null || currentLib.length()==0)
        currentLib = "*CURUSR";
      else if (currentLib.length() > 10)
      {
          Trace.log(Trace.ERROR, "Length of parameter 'currentLib' is not valid: '" + currentLib + "'");
          throw new ExtendedIllegalArgumentException("setIASPGroup currentLib (" + currentLib + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
      }
      
      if(librariesForThread==null || librariesForThread.length()==0)
        librariesForThread = "*CURUSR";
      else if (librariesForThread.length() > 10)
      {
          Trace.log(Trace.ERROR, "Length of parameter 'librariesForThread' is not valid: '" + librariesForThread + "'");
          throw new ExtendedIllegalArgumentException("setIASPGroup librariesForThread (" + librariesForThread + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
      }
      
      this.currentLib_ = currentLib;
      this.librariesForThread_ = librariesForThread;

      String SetASPGrp = "SETASPGRP ASPGRP("+ IASPGroup + ") CURLIB("+currentLib+") USRLIBL("+librariesForThread+")"; //@P2C Default value *CURSYSBAS will override the user profile/jobd set libs.
      Trace.log(Trace.DIAGNOSTIC,"Call command of setaspgrp "+SetASPGrp);
      CommandCall commandCall = new CommandCall(this);
      if (commandCall.run(SetASPGrp) != true) {
        Trace.log(Trace.ERROR, this,"Command SETASPGRP Failed with iasp "+IASPGroup);
      }  else
        aspName = IASPGroup;
    }
    
    /**
     * Set ASP group for the AS400 connection to the Remote Command Host server. It calls SETASPGRP command to change
     * the asp setting for corresponding CommandCall, ProgramCall and ServiceProgramCall in the same connection. If an
     * ASP group had already been set, it will remove the old ASP group and set the specified ASP group for the current
     * thread. Once the specified ASP group has been set, all libraries in the independent ASPs in the ASP group are
     * accessible and objects in those libraries can be referenced using regular library-qualified object name syntax.
     * 
     * @param IASPGroup          asp group name
     * @param currentLib         Current library which can be *CURSYSBAS, *CURUSR, *CRTDFT, name. If null or "" is set,
     *                           default value *CURUSR is used.
     * @param librariesForThread Libraries for current thread. If null is set, default value *CURUSR is used. Up to 250
     *                           libraries can be set.
     * @throws AS400SecurityException          If a security or authority error occurs.
     * @throws ErrorCompletingRequestException If an error occurs before the request is completed.
     * @throws IOException                     If an error occurs while communicating with the system.
     * @throws InterruptedException            If this thread is interrupted.
     * @throws PropertyVetoException           If the recipient wishes the property change to be rolled back.
     */
    public void setIASPGroup(String IASPGroup, String currentLib, String[] librariesForThread) throws AS400SecurityException, ErrorCompletingRequestException, IOException, InterruptedException, PropertyVetoException
    {
      if(currentLib==null || currentLib.length()==0)
        currentLib = "*CURUSR";
      else if (currentLib.length() > 10)
        {
            Trace.log(Trace.ERROR, "Length of parameter 'currentLib' is not valid: '" + currentLib + "'");
            throw new ExtendedIllegalArgumentException("setIASPGroup currentLib (" + currentLib + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
        }
      
      if(librariesForThread==null || librariesForThread.length==0)
        librariesForThread = new String[]{"*CURUSR"};
      else if(librariesForThread.length>1)
      {
        if(librariesForThread.length>250){
          Trace.log(Trace.ERROR, this,"Up to 250 libraries can be set for SETASPGRP USRLIBL");
          throw new ExtendedIllegalArgumentException("setIASPGroup Libraries for current thread librariesForThread(" + librariesForThread.length + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
        }
        else
          for(int i=0;i<librariesForThread.length;i++)
          {
            String value =librariesForThread[i];
            if (value==null || value.length()<1 || value.length()> 10)
            {
                Trace.log(Trace.ERROR, "Length of parameter in 'librariesForThread' is not valid: '" + value + "'");
                throw new ExtendedIllegalArgumentException("setIASPGroup parameter in librariesForThread (" + value + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
            }
            value=value.toUpperCase();
            if(value.equals(USRLIBL_SINGLE_VALUE[0]) || value.equals(USRLIBL_SINGLE_VALUE[1])|| value.equals(USRLIBL_SINGLE_VALUE[2]) || value.equals(USRLIBL_SINGLE_VALUE[3])){
              Trace.log(Trace.ERROR, this,value+" must be only value for parameter USRLIBL.");
              throw new ExtendedIllegalArgumentException("SETASPGRP USRLIBL parameter value (" + value + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);
            }
          }
      }

      this.currentLib_ = currentLib;
      this.librariesForThread_ = "";
      for(int i=0;i<librariesForThread.length;i++)
        this.librariesForThread_+=librariesForThread[i].toUpperCase() + " ";
      this.librariesForThread_ = this.librariesForThread_.substring(0, this.librariesForThread_.length()-1)  ;

      String SetASPGrp = "SETASPGRP ASPGRP("+ IASPGroup + ") CURLIB("+currentLib+") USRLIBL("+librariesForThread_ +")"; //@P2C Default value *CURSYSBAS will override the user profile/jobd set libs.
      Trace.log(Trace.DIAGNOSTIC,"Call command of setaspgrp "+SetASPGrp);
      CommandCall commandCall = new CommandCall(this);
      if (commandCall.run(SetASPGrp) != true) {
        Trace.log(Trace.ERROR, this,"Command SETASPGRP Failed with iasp "+IASPGroup);
      }  else
        aspName = IASPGroup;
    }
    
    // Private constructor for use when a new object is needed and the password is already twiddled.
    // Used by password cache and password verification code.
    private AS400(String systemName, String userId, CredentialVault pwVault, char[] additionalAuthFactor)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing internal AS400 object, system name: '" + systemName + "' user ID: '" + userId + "'");
        if (PASSWORD_TRACE) Trace.log(Trace.DIAGNOSTIC, pwVault.trace());
        // System name and user ID validation has been deferred to here.
        if (systemName == null)
            throw new NullPointerException("systemName");

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        setAdditionalAuthenticationFactor(additionalAuthFactor);

        construct();
        systemName_ = systemName;
        systemNameLocal_ = resolveSystemNameLocal(systemName);

        if (isTurkish()) {
            userId = userId.toUpperCase(Locale.ENGLISH);
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }

        userId_ = userId.toUpperCase();

        // Create a copy of the supplied credential vault.  This allows the AS400
        // object to have its own credential vault object while making the vault
        // contain the the same credential as the original vault we have been provided.
        // It is VERY important that the copy of the vault contain the same credential
        // as the original vault.  This is because the Toolbox implementation has
        // always allowed two AS400 objects to share a credential (i.e. two AS400
        // objects can both share the same password credential).  So we must maintain
        // that behavior, but we need to do so using two different credential vaults,
        // because each AS400 object must always have its very own credential vault.
        credVault_ = pwVault.clone();
        proxyServer_ = resolveProxyServer(proxyServer_);
    }

    /**
     * Constructs an AS400 object. It uses the specified system name, user ID, and password. No sign-on prompt is
     * displayed unless the sign-on fails.
     * 
     * @param systemName  The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId      The user profile name to use to authenticate to the system. If running on IBM i, *CURRENT may
     *                    be used to specify the current user ID.
     * @param password    The user profile password to use to authenticate to the system. If running on IBM i, *CURRENT
     *                    may be used to specify the current user ID.
     * @param proxyServer The name and port of the proxy server in the format <code>serverName[:port]</code>. If no port
     *                    is specified, a default will be used.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     * @deprecated Use AS400((String systemName, String userId, char[] password, String proxyServer) instead.
     **/
    @Deprecated
    public AS400(String systemName, String userId, String password, String proxyServer)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object with password, system name: '" + systemName + "' user ID: '" + userId + "' proxy server: '" + proxyServer + "'");
        if (PASSWORD_TRACE) Trace.log(Trace.DIAGNOSTIC, "password: '" + password + "'");
        if (systemName == null)
            throw new NullPointerException("systemName");

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        char[] passwordChars = (password == null) ? null : password.toCharArray(); 
        
        try
        {
            checkPasswordNullAndLength(passwordChars, "password");
            if (proxyServer == null)
                throw new NullPointerException("proxyServer");
    
            construct();
            systemName_ = systemName;
            systemNameLocal_ = resolveSystemNameLocal(systemName);
    
            if (isTurkish()) {
                userId = userId.toUpperCase(Locale.ENGLISH);
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
            }
    
            userId_ = userId.toUpperCase();

            credVault_ = new PasswordVault(passwordChars);
        } 
        finally {
            PasswordVault.clearArray(passwordChars);
        }
        
        proxyServer_ = resolveProxyServer(proxyServer);
    }

    /**
     * Constructs an AS400 object. It uses the specified system name, user ID, and password. No sign-on prompt is
     * displayed unless the sign-on fails.
     * 
     * @param systemName  The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId      The user profile name to use to authenticate to the system. If running on IBM i, *CURRENT may
     *                    be used to specify the current user ID.
     * @param password    The user profile password to use to authenticate to the system. The caller is responsible fore
     *                    clearing sensitive data from password after the constructor runs.
     * @param proxyServer The name and port of the proxy server in the format <code>serverName[:port]</code>. If no port
     *                    is specified, a default will be used.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     **/
    public AS400(String systemName, String userId, char[] password, String proxyServer)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object with password, system name: '" + systemName + "' user ID: '" + userId + "' proxy server: '" + proxyServer + "'");
        if (systemName == null)
            throw new NullPointerException("systemName");

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        checkPasswordNullAndLength(password, "password");
        
        if (proxyServer == null)
            throw new NullPointerException("proxyServer");

        construct();
        systemName_ = systemName;
        systemNameLocal_ = resolveSystemNameLocal(systemName);

        if (isTurkish()) {
          userId = userId.toUpperCase(Locale.ENGLISH);
          if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }

        userId_ = userId.toUpperCase();
        credVault_ = new PasswordVault(password);
        proxyServer_ = resolveProxyServer(proxyServer);
    }


    /**
     * Constructs an AS400 object. This does not create a clone. The new AS400 object will 
     * uses the same system name, user ID, and other properties of the passed-in AS400 object, 
     * enabling the new object to generally behave in a similar manner as the passed-in AS400 object. 
     * <P>
     * <b>Notes:</b>
     * <ul>
     * <li>Host server connections are not shared between the passed-in AS400 object and the new AS400 object, except
     *     for the HOSTCNN service connection, which is shared as long as the authentication credentials 
     *     stay the same. 
     * <li>Properties that are not propagated to the new AS400 object include event listeners (connection, property change, 
     *     vetoable property change.). In addition, you will need to call the {@link #setStayAlive(long)} method on the new 
     *     AS400 object if you want the feature enabled. 
     * </ul>
     * 
     * @param system A previously instantiated AS400 object.
     **/
    public AS400(AS400 system)
    {
        super();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing AS400 object, system: " + system);
        if (system == null)
            throw new NullPointerException("system");

        construct();
        systemName_ = system.systemName_;
        systemNameLocal_ = system.systemNameLocal_;
        userId_ = system.userId_;

        // Create a copy of the supplied credential vault.  This allows the AS400
        // object to have its own credential vault object while making the vault
        // contain the the same credential as the original vault we have been provided.
        // It is VERY important that the copy of the vault contain the same credential
        // as the original vault.  This is because the Toolbox implementation has
        // always allowed two AS400 objects to share a credential (i.e. two AS400
        // objects can both share the same password credential).  So we must maintain
        // that behavior, but we need to do so using two different credential vaults,
        // because each AS400 object must always have its very own credential vault.
        credVault_ = (CredentialVault)system.credVault_.clone();
        
        additionalAuthenticationFactor_ = system.additionalAuthenticationFactor_;

        gssCredential_ = system.gssCredential_;
        gssName_ = system.gssName_;
        gssOption_ = system.gssOption_;

        proxyServer_ = system.proxyServer_;
        // proxyClientConnection_ is not copied.

        guiAvailable_ = system.guiAvailable_;
        usePasswordCache_ = system.usePasswordCache_;
        useDefaultUser_ = system.useDefaultUser_;
        showCheckboxes_ = system.showCheckboxes_;

        // If passed in system has SSL options, deep copy them if this instance is secure
        if (system.isSecure())
        	useSSLConnection_= new SSLOptions(system.useSSLConnection_); 
        
        mustAddLanguageLibrary_ = system.mustAddLanguageLibrary_;
        mustUseSockets_ = system.mustUseSockets_;
        mustUseNetSockets_ = system.mustUseNetSockets_;
        mustUseSuppliedProfile_ = system.mustUseSuppliedProfile_;
        threadUsed_ = system.threadUsed_;
        locale_ = system.locale_;
        nlv_ = system.nlv_;
        socketProperties_.copyValues(system.socketProperties_);
        ccsid_ = system.ccsid_;

        // connectionListeners_ is not copied.
        // dispatcher_ is not copied.
        // propertyChangeListeners_ is not copied.
        // vetoableChangeListeners_ is not copied.

        // propertiesFrozen_ is not copied.
        // impl_ is not copied.
        // signonInfo_ is not copied.
        
        ddmRDB_ = system.ddmRDB_;
    
        if (system.impl_ != null && system.impl_.isConnected(AS400.HOSTCNN))
        {
            impl_ = (AS400Impl)loadImpl2("com.ibm.as400.access.AS400ImplRemote", "com.ibm.as400.access.AS400ImplProxy");
            signonInfo_ = impl_.setState(system.impl_, credVault_);
            
            // Do not freeze properties as these need to be changed for new JDBC connections.
        }
    }

    /**
     * Returns a new instance of an AS400 object. 
     * <p>
     * If running on IBM i, the target is the local system. This has the same effect as using <code>localhost</code> for
     * the system name, *CURRENT for the user ID, and *CURRENT for the password.
     * <p>
     * If running on another operating system, a sign-on prompt may be displayed. The user is then able to specify the
     * system name, user ID, and password. 
     * @param useSSL     Whether or not the new AS400 object should use secure connections when communicating with the
     *                   host servers.
     * @return AS400 object.
     **/
    public static AS400 newInstance(boolean useSSL)
    {
        return (useSSL) ? new SecureAS400() 
                        : new AS400();
    }
    
    /**
     * Returns a new instance of an AS400 object. It uses the specified system name.
     * <p>
     * If running on IBM i to another system or to itself, the user ID and password of the current job are used.
     * <p>
     * If running on another operating system, the user may be prompted for the user ID and password if a default user
     * has not been established for this system name.
     * 
     * @param useSSL     Whether or not the new AS400 object should use secure connections when communicating with the
     *                   host servers.
     * @param systemName The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @return AS400 object.
     **/
    public static AS400 newInstance(boolean useSSL, String systemName)
    {
        return (useSSL) ? new SecureAS400(systemName) 
                        : new AS400(systemName);
    }
    
    /**
     * Returns a new instance of an AS400 object. It uses the specified system name, and user ID. When the sign-on
     * prompt is displayed, the user is able to specify the password. Note that the user ID may be overridden in the
     * AS400 object.
     * 
     * @param useSSL     Whether or not the new AS400 object should use secure connections when communicating with the
     *                   host servers.
     * @param systemName The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId     The user profile name to use to authenticate to the system. If running on IBM i, *CURRENT may
     *                   be used to specify the current user ID.
     * @return AS400 object.
     * @throws IOException            If an error occurs while communicating with the system.
     * @throws AS400SecurityException If a security or authority error occurs.
     **/
    public static AS400 newInstance(boolean useSSL, String systemName, String userId) throws IOException, AS400SecurityException
    {
        return (useSSL) ? new SecureAS400(systemName, userId) 
                        : new AS400(systemName, userId);
    }

    /**
     * Returns a new instance of an AS400 object. It uses the specified system name, user ID, password, and additional
     * authentication factor. No sign-on prompt is displayed unless the sign-on fails.
     * 
     * @param useSSL               Whether or not the new AS400 object should use secure connections when communicating
     *                             with the host servers.
     * @param systemName           The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId               The user profile name to use to authenticate to the system. If running on IBM i,
     *                             *CURRENT may be used to specify the current user ID.
     * @param password             The user profile password to use to authenticate to the system. The caller is
     *                             responsible for clearing the password array to keep the password from residing in
     *                             memory.
     * @param additionalAuthFactor Additional authentication factor (or null if not providing one).
     * @return AS400 object.
     * @throws IOException            If an error occurs while communicating with the system.
     * @throws AS400SecurityException If a security or authority error occurs.
     **/
    public static AS400 newInstance(boolean useSSL, String systemName, String userId, char[] password, char[] additionalAuthFactor) throws IOException, AS400SecurityException
    {
        return (useSSL) ? new SecureAS400(systemName, userId, password, additionalAuthFactor) 
                        : new AS400(systemName, userId, password, additionalAuthFactor);
    }
    
    /**
     * Returns a new instance of an AS400 object. It uses the specified system name, user ID, and password. No sign-on
     * prompt is displayed unless the sign-on fails.
     * 
     * @param useSSL      Whether or not the new AS400 object should use secure connections when communicating with the
     *                    host servers.
     * @param systemName  The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param userId      The user profile name to use to authenticate to the system. If running on IBM i, *CURRENT may
     *                    be used to specify the current user ID.
     * @param password    The user profile password to use to authenticate to the system. The caller is responsible fore
     *                    clearing sensitive data from password after the constructor runs.
     * @param proxyServer The name and port of the proxy server in the format <code>serverName[:port]</code>. If no port
     *                    is specified, a default will be used.
     * @return AS400 object.
     **/
    public static AS400 newInstance(boolean useSSL, String systemName, String userId, char[] password, String proxyServer)
    {
        return (useSSL) ? new SecureAS400(systemName, userId, password, proxyServer) 
                        : new AS400(systemName, userId, password, proxyServer);
    }
    
    /**
     * Returns a new instance of an AS400 object. It uses the specified system name and profile token.
     * 
     * @param useSSL       Whether or not the new AS400 object should use secure connections when communicating with the
     *                     host servers.
     * @param systemName   The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @param profileToken The profile token to use to authenticate to the system.
     * @return AS400 object.
     **/
    public static AS400 newInstance(boolean useSSL, String systemName, ProfileTokenCredential profileToken)   
    {
        return (useSSL) ? new SecureAS400(systemName, profileToken) 
                        : new AS400(systemName, profileToken);
    }
    
    /**
     * Returns a new instance of an AS400 object. It uses the same system name and user ID. This does not create a
     * clone. The new object has the same behavior, but results in a new set of socket connections.
     * 
     * @param useSSL Whether or not the new AS400 object should use secure connections when communicating with the host
     *               server.
     * @param system A previously instantiated AS400 object.
     * @return AS400 object.
     **/
    public static AS400 newInstance(boolean useSSL, AS400 system)   
    {
        return (useSSL) ? new SecureAS400(system) 
                        : new AS400(system);
    }

    /**
     * Adds a listener to be notified when a connection event occurs.
     * 
     * @param listener The listener object.
     **/
    public void addConnectionListener(ConnectionListener listener)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Adding connection listener.");
        if (listener == null)
        {
            throw new NullPointerException("listener");
        }
        synchronized (this)
        {
            // If first add.
            if (connectionListeners_ == null)
            {
                connectionListeners_ = new Vector();
                dispatcher_ = new ConnectionListener()
                {
                    public void connected(ConnectionEvent event) {
                        fireConnectEvent(event, true);
                    }
                    public void disconnected(ConnectionEvent event) {
                        fireConnectEvent(event, false);
                    }
                };
            }
            // If this is the first add and we are already connected.
            if (impl_ != null && connectionListeners_.isEmpty())
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Enabling connection listener dispatcher.");
                impl_.addConnectionListener(dispatcher_);
            }
            connectionListeners_.addElement(listener);
        }
    }

    /**
     * Validates the user ID and password, and if successful, adds the information to the password cache.
     * 
     * @param systemName The name of the IBM i system.
     * @param userId     The user profile name.
     * @param password   The user profile password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @deprecated Use addPasswordCacheEntry(String systemName, String userId, char[] password) instead
     **/
    public static void addPasswordCacheEntry(String systemName, String userId, String password) throws AS400SecurityException, IOException
    {
        addPasswordCacheEntry(systemName, userId, (password == null) ? (char[])null : password.toCharArray(), false);
    }

    /**
     * Validates the user ID and password, and if successful, adds the information to the password cache.
     * 
     * @param systemName The name of the IBM i system.
     * @param userId     The user profile name.
     * @param password   The user profile password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public static void addPasswordCacheEntry(String systemName, String userId, char[] password) throws AS400SecurityException, IOException
    {
        addPasswordCacheEntry(systemName, userId, password, false);
    }

    /**
     * Validates the user ID and password, and if successful, adds the information to the password cache.
     * 
     * @param systemName The name of the IBM i system.
     * @param userId     The user profile name.
     * @param password   The user profile password.
     * @param useSSL     Whether or not secure connections should be used when communicating with the host servers.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public static void addPasswordCacheEntry(String systemName, String userId, char[] password, boolean useSSL) throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Adding password cache entry, system name: '" + systemName + "' user ID: '" + userId + "'" + " useSSL: " + useSSL);
        addPasswordCacheEntry((useSSL) ? new SecureAS400(systemName, userId, password) : new AS400(systemName, userId, password));
    }    
    
    /**
     * Validates the user ID and password, and if successful, adds the information to the password cache.
     * 
     * @param systemName  The name of the IBM i system.
     * @param userId      The user profile name.
     * @param password    The user profile password.
     * @param proxyServer The name and port of the proxy server in the format <code>serverName[:port]</code>. If no port
     *                    is specified, a default will be used.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @deprecated Use addPasswordCacheEntry(String systemName, String userId, char[] password, String proxyServer)
     *             instead.
     **/
    @Deprecated
    public static void addPasswordCacheEntry(String systemName, String userId, String password, String proxyServer) throws AS400SecurityException, IOException
    {
        addPasswordCacheEntry(systemName, userId, (password == null) ? (char[])null : password.toCharArray(), proxyServer, false);
    }

    /**
     * Validates the user ID and password, and if successful, adds the information to the password cache.
     * 
     * @param systemName  The name of the IBM i system.
     * @param userId      The user profile name.
     * @param password    The user profile password.
     * @param proxyServer The name and port of the proxy server in the format <code>serverName[:port]</code>. If no port
     *                    is specified, a default will be used.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
   public static void addPasswordCacheEntry(String systemName, String userId, char[] password, String proxyServer) throws AS400SecurityException, IOException
   {
       addPasswordCacheEntry(systemName, userId, password, proxyServer, false);
   }
   
   /**
    * Validates the user ID and password, and if successful, adds the information to the password cache.
    * 
    * @param systemName  The name of the IBM i system.
    * @param userId      The user profile name.
    * @param password    The user profile password.
    * @param proxyServer The name and port of the proxy server in the format <code>serverName[:port]</code>. If no port
    *                    is specified, a default will be used.
    * @param useSSL      Whether or not secure connections should be used when communicating with the host servers.
    * @exception AS400SecurityException If a security or authority error occurs.
    * @exception IOException            If an error occurs while communicating with the system.
    **/
    public static void addPasswordCacheEntry(String systemName, String userId, char[] password, String proxyServer, boolean useSSL) throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Adding password cache entry, system name: '" + systemName + "' user ID: '" + userId + "' proxy server: '" + proxyServer + "'" + "' useSSL: '" + useSSL);
        addPasswordCacheEntry((useSSL) ? new SecureAS400(systemName, userId, password, proxyServer) : new AS400(systemName, userId, password, proxyServer));
    }

    private static void addPasswordCacheEntry(AS400 system) throws AS400SecurityException, IOException
    {
        system.validateSignon();  // Exception thrown if info not valid.
        setCacheEntry(system.systemName_, system.userId_, system.credVault_);
    }

    /**
     * Adds a listener to be notified when the value of any property is changed.
     * 
     * @param listener The listener object.
     **/
    public void addPropertyChangeListener(PropertyChangeListener listener)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Adding property change listener.");
        if (listener == null)
            throw new NullPointerException("listener");

        synchronized (this)
        {
            // If first add.
            if (propertyChangeListeners_ == null)
                propertyChangeListeners_ = new PropertyChangeSupport(this);

            propertyChangeListeners_.addPropertyChangeListener(listener);
        }
    }

    /**
     * Adds a listener to be notified when the value of any constrained property is changed. The vetoableChange method
     * will be called.
     * 
     * @param listener The listener object.
     **/
    public void addVetoableChangeListener(VetoableChangeListener listener)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Adding vetoable change listener.");
        if (listener == null)
            throw new NullPointerException("listener");

        synchronized (this)
        {
            // If first add.
            if (vetoableChangeListeners_ == null)
                vetoableChangeListeners_ = new VetoableChangeSupport(this);

            vetoableChangeListeners_.addVetoableChangeListener(listener);
        }
    }

    /**
     * Indicates if properties are frozen. If this is true, property changes should not be made. Properties are not the
     * same thing as attributes. Properties are basic pieces of information which must be set to make the object usable,
     * such as the system name, user ID or other properties that identify the resource.
     * 
     * @return true if properties are frozen, false otherwise.
     **/
    public boolean arePropertiesFrozen()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if properties are frozen:", propertiesFrozen_);
        return propertiesFrozen_;
    }

    /**
     * Checks whether an additional authentication factor is accepted for the given system. The communications with the
     * host server is done over a secure channel if the AS400 object was created to use SSL; otherwise, the
     * communications with the host server is done over an unsecure channel.
     * 
     * @return whether the server accepts the additional authentication factor
     * @exception IOException If an error occurs while communicating with the system.
     * @throws AS400SecurityException If an error occurs exchanging client/server information
     **/
    public boolean isAdditionalAuthenticationFactorAccepted() throws IOException, AS400SecurityException {
        return isAdditionalAuthenticationFactorAccepted(getSystemName(), isSecure());
    }
   
    /**
     * Checks whether an additional authentication factor is accepted for the given system
     * 
     * @param systemName The IP address or hostname of the target system
     * @param useSSL     Whether or not secure connections should be used when communicating with the host servers.
     * @return whether the server accepts the additional authentication factor
     * @exception IOException If an error occurs while communicating with the system.
     * @throws AS400SecurityException If an error occurs exchanging client/server information
     **/
    public static boolean  isAdditionalAuthenticationFactorAccepted(String systemName, boolean useSSL) throws IOException, AS400SecurityException {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting whether additional factor accepted: " + systemName + ", use SSL:", useSSL);
        return AS400ImplRemote.getAdditionalAuthenticationIndicator(systemName, useSSL);
    }
    
    /**
     * Checks whether an additional authentication factor is accepted for the given system
     * 
     * @param systemName The IP address or hostname of the target system
     * @return whether the server accepts the additional authentication factor
     * @exception IOException If an error occurs while communicating with the system.
     * @throws AS400SecurityException If an error occurs exchanging client/server information
     * @deprecated Use {@link #isAdditionalAuthenticationFactorAccepted(String, boolean)}
     **/
    @Deprecated
    public static boolean  isAdditionalAuthenticationFactorAccepted(String systemName) throws IOException, AS400SecurityException {
       return isAdditionalAuthenticationFactorAccepted(systemName, false);
    }

    /**
     * Authenticate the user ID and password on the system and adds to the signon-list. This means that authentication
     * is performed using the AS400 object instance for which this method is invoked - a new AS400 object is not created
     * to do the authentication (unlike the other authenticate(String, String) or validateSignon() methods).
     * <P>
     * If the AS400 object has previously been authenticated, either by using authenticate() or indirectly such as when
     * establishing a connection to a host server, and if any of the setter methods are used after the authentication to
     * change credentials, such as user ID or system name, the AS400.HOSTCNN and/or AS400.SIGNON server connections, if
     * they exist, may be discarded if the change is different than what was previously used in the authentication
     * process.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * <p>
     * <b>Note:</b>If an additional authentication factor has been set in the AS400 object, it is used.
     * 
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception ExtendedIllegalStateException  If system name or user ID not set.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public boolean authenticate() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Authenticating signon.");

        if (systemName_.length() == 0 && !systemNameLocal_)
        {
            Trace.log(Trace.ERROR, "Cannot authenticate signon before system name is set.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }
        
        userId_ = resolveUserId(userId_);
        if (userId_.length() == 0)
        {
            Trace.log(Trace.ERROR, "Cannot authenticate signon before user ID is set.");
            throw new ExtendedIllegalStateException("userId", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }
        
        return validateSignon(false, userId_, credVault_, additionalAuthenticationFactor_);
    }
    
    /**
     * Authenticates the user profile name and user profile password.
     * <p>
     * This method is functionally equivalent to the <i>validateSignon()</i> method, except it does not alter the user
     * profile assigned to this object, impact the status of existing connections, or otherwise impact the user and
     * authorities on which the application is running.
     * <p>
     * The system name needs to be set prior to calling this method.
     * <p>
     * <b>Note:</b> Providing an incorrect password increments the number of failed sign-on attempts for the user
     * profile, and can result in the profile being disabled.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * 
     * @param userId   The user profile name.
     * @param password The user profile password.
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @deprecated Using a String as a password is insecure.
     **/
    @Deprecated
    public boolean authenticate(String userId, String password) throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Authenticating signon information:", userId);
        return validateSignon(userId, password);
    }

    
    /**
     * Authenticates the user profile name and user profile password.
     * <p>
     * This method is functionally equivalent to the <i>validateSignon()</i> method, except it does not alter the user
     * profile assigned to this object, impact the status of existing connections, or otherwise impact the user and
     * authorities on which the application is running.
     * <p>
     * The system name needs to be set prior to calling this method.
     * <p>
     * <b>Note:</b> Providing an incorrect password increments the number of failed sign-on attempts for the user
     * profile, and can result in the profile being disabled.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * 
     * @param userId   The user profile name.
     * @param password The user profile password.
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public boolean authenticate(String userId, char[] password) throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Authenticating signon information:", userId);
        return validateSignon(userId, password);
    }

    // Only load native version once.
    private static int nativeVersion = -1;
    private static int getNativeVersion()
    {
        try
        {
            if (AS400.nativeVersion == -1)
                AS400.nativeVersion = Class.forName("com.ibm.as400.access.NativeVersion").newInstance().hashCode();
        }
        catch (ClassNotFoundException e)
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Not using native optimizations; class 'NativeVersion' is not found.");
            AS400.nativeVersion = 0;
        }
        catch (Exception e)
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Not using native optimizations; unexpected exception while loading native version:", e);
            AS400.nativeVersion = 0;
        }
        return AS400.nativeVersion;
    }

    /**
     * Indicates if this AS400 object is enabled to exploit Toolbox native optimizations. This requires that the native
     * optimization classes are available on the classpath, and this AS400 object represents the local system and is
     * configured to allow the native optimizations to be used. Note: If the authentication scheme is other than
     * {@link #AUTHENTICATION_SCHEME_PASSWORD AUTHENTICATION_SCHEME_PASSWORD}, native optimizations will not be used.
     * 
     * @return true if the native optimizations can be used; false otherwise.
     * @see #isLocal
     * @see #isMustUseSockets
     * @see #getAuthenticationScheme
     **/
    public boolean canUseNativeOptimizations()
    {        
        if (AS400.onAS400 && !mustUseSockets_ && systemNameLocal_ && proxyServer_.length() == 0 
                && credVault_.getType() == AUTHENTICATION_SCHEME_PASSWORD && getNativeVersion() == 2)
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Using native optimizations.");
            return true;
        }
        
        if (Trace.traceOn_)
        {
            Trace.log(Trace.DIAGNOSTIC, "Not using native optimizations. Reason follows:");
            if (!AS400.onAS400) Trace.log(Trace.DIAGNOSTIC, "  onAS400:", AS400.onAS400);
            
            if (mustUseSockets_) Trace.log(Trace.DIAGNOSTIC, "  mustUseSockets:", mustUseSockets_);

            if (!systemNameLocal_)  Trace.log(Trace.DIAGNOSTIC, "  systemNameLocal:", systemNameLocal_);

            if (proxyServer_.length() != 0) Trace.log(Trace.DIAGNOSTIC, "  proxyServer:", proxyServer_);

            int credType = credVault_.getType();
            if (credType != AUTHENTICATION_SCHEME_PASSWORD) {
              // Design note: For various reasons (such as lack of requirement, and potential complications
              // when swapping during a token-based session), the Toolbox has never supported staying
              // on-thread when using profile tokens or other non-password based authentication schemes.
              Trace.log(Trace.DIAGNOSTIC, "  authenticationScheme:", credType + " ("+credTypeToString(credType)+")");
            }
            
            if (getNativeVersion() != 2) Trace.log(Trace.DIAGNOSTIC, "  nativeVersion:", getNativeVersion());
        }
        
        return false;
    }

    private static final String credTypeToString(int credType)
    {
      String result;
      switch (credType)
      {
        case AUTHENTICATION_SCHEME_PASSWORD :
          result = "password";
          break;
        case AUTHENTICATION_SCHEME_GSS_TOKEN :
          result = "GSS token";
          break;
        case AUTHENTICATION_SCHEME_PROFILE_TOKEN :
          result = "profile token";
          break;
        case AUTHENTICATION_SCHEME_IDENTITY_TOKEN :
          result = "identity token";
          break;
        default :
          result = "unrecognized";
      }
      return result;
    }

    /**
     * Changes the user profile password. The system name and user profile name need to be set prior to calling this
     * method.
     * 
     * @param oldPassword The old user profile password.
     * @param newPassword The new user profile password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @deprecated Use changePassword(char[] oldPassword, char[] newPassword) instead
     **/
    @Deprecated
    public void changePassword(String oldPassword, String newPassword) throws AS400SecurityException, IOException
    {
        char[] oldPasswordChars = (oldPassword == null) ? null : oldPassword.toCharArray();
        char[] newPasswordChars = (newPassword == null) ? null : newPassword.toCharArray();

        try {
            changePassword(oldPasswordChars, newPasswordChars, null);
        }
        finally {
            PasswordVault.clearArray(oldPasswordChars);
            PasswordVault.clearArray(newPasswordChars);  
        }
    }

    
    /**
     * Changes the user profile password. The system name and user profile name need to be set prior to calling this
     * method.
     * 
     * @param oldPassword The old user profile password.
     * @param newPassword The new user profile password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public void changePassword(char[] oldPassword, char[] newPassword) throws AS400SecurityException, IOException
    {
        changePassword(oldPassword, newPassword, null);
    }

    
    /**
     * Changes the user profile password. The system name and user profile name need to be set prior to calling this
     * method.
     * 
     * @param oldPassword          The old user profile password.
     * @param newPassword          The new user profile password.
     * @param additionalAuthFactor Additional authentication factor (or null if not providing one).
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception ExtendedIllegalStateException  If system name or user ID not set.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public void changePassword(char[] oldPassword, char[] newPassword, char[] additionalAuthFactor) throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Changing password.");
        if (PASSWORD_TRACE)
        {
            Trace.log(Trace.DIAGNOSTIC, "oldPassword: '" + new String(oldPassword) + "'");
            Trace.log(Trace.DIAGNOSTIC, "newPassword: '" + new String(newPassword) + "'");
        }
        
        checkPasswordNullAndLength(oldPassword, "oldPassword");
        checkPasswordNullAndLength(newPassword, "newPassword");
        if (systemName_.length() == 0 && !systemNameLocal_)
        {
            Trace.log(Trace.ERROR, "Cannot change password before system name is set.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }
        
        if (additionalAuthFactor != null && additionalAuthFactor.length > ProfileTokenCredential.MAX_ADDITIONALAUTHENTICATIONFACTOR_LENGTH)
            throw new ExtendedIllegalArgumentException("additionalAuthFactor", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        userId_ = resolveUserId(userId_);
        if (userId_.length() == 0)
        {
            Trace.log(Trace.ERROR, "Cannot change password before user ID is set.");
            throw new ExtendedIllegalStateException("userId", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }

        chooseImpl();

        // Synchronize to protect sign-on information.
        synchronized (this)
        {
            byte[] proxySeed = new byte[9];
            CredentialVault.rng.nextBytes(proxySeed);
            byte[] remoteSeed = impl_.exchangeSeed(proxySeed);

            if (PASSWORD_TRACE)
            {
                Trace.log(Trace.DIAGNOSTIC, "AS400 object proxySeed:", proxySeed);
                Trace.log(Trace.DIAGNOSTIC, "AS400 object remoteSeed:", remoteSeed);
            }

            // Note that in this particular case it is OK to just pass byte arrays
            // instead of credential vaults.  That is because we have the clear text
            // passwords, so all we need to do is encode them and send them over
            // to the impl.  After the password has been changed, we will update
            // our own credential vault with the new password, and create ourselves
            // the appropriate type of credential vault to store the password in.
  
            byte[] oldbytes = null;
            byte[] newbytes = null;
            try 
            {
                oldbytes = BinaryConverter.charArrayToByteArray(oldPassword); 
                newbytes = BinaryConverter.charArrayToByteArray(newPassword); 
                
                byte[] encodeOldBytes = CredentialVault.encode(proxySeed, remoteSeed, oldbytes);
                byte[] encodeNewBytes = CredentialVault.encode(proxySeed, remoteSeed, newbytes);

                signonInfo_ = impl_.changePassword(systemName_,  systemNameLocal_, userId_,  
                                               encodeOldBytes,  encodeNewBytes,  additionalAuthFactor);
            }
            finally {
                CredentialVault.clearArray(oldbytes); 
                CredentialVault.clearArray(newbytes);
            }

            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Password changed successfully.");

            // Update credential vault with new password.
            credVault_.empty();
            credVault_ = new PasswordVault(newPassword);
        }
    }



    // Choose between remote and proxy implementation objects, set state information into remote implementation object.  
    // Synchronized to protect impl_ and propertiesFrozen_ instance variables.  This method can safely be called multiple 
    // times because it checks its state before performing the code.
    private synchronized void chooseImpl()
    {
        if (impl_ == null)
        {
            impl_ = (AS400Impl)loadImpl2("com.ibm.as400.access.AS400ImplRemote", "com.ibm.as400.access.AS400ImplProxy");

            // If there is a connection listener.  Connect the remote implementation connection events to this object.
            if (connectionListeners_ != null && !connectionListeners_.isEmpty())
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Enabling connection listener dispatcher.");
                impl_.addConnectionListener(dispatcher_);
            }
        }
        
        if (!propertiesFrozen_)
        {
            impl_.setState(useSSLConnection_, canUseNativeOptimizations(), threadUsed_, ccsid_, nlv_, 
                           socketProperties_, ddmRDB_, mustUseNetSockets_, mustUseSuppliedProfile_, mustAddLanguageLibrary_);
            propertiesFrozen_ = true;
        }
        
        impl_.setBidiStringType(getBidiStringType());
        impl_.setAdditionalAuthenticationFactor(additionalAuthenticationFactor_);
    }

    /**
     * Clears the password cache for all systems within this Java virtual machine.
     **/
    public static void clearPasswordCache()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Clearing password cache.");
        synchronized (AS400.systemList)
        {
            AS400.systemList.removeAllElements();
        }
    }

    /**
     * Clears all the passwords that are cached for the given system name within this Java virtual machine.
     * 
     * @param systemName The name of the IBM i system.
     **/
    public static void clearPasswordCache(String systemName)
    {
        String longName = null;
        boolean isLocalHost = false;
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Clearing password cache, system name:", systemName);
        if (systemName == null)
            throw new NullPointerException("systemName");

        systemName = resolveSystem(systemName);
        boolean localHost = systemName.equals("localhost");
        if (localHost)
        {
            isLocalHost = true;
            try {
                systemName = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) { /* ignore */ }
        }
        
        int dotIndex = systemName.indexOf(".");
        if (dotIndex > 0) {
            longName = systemName;
            systemName = systemName.substring(0,dotIndex);
        }
        
        synchronized (AS400.systemList)
        {
            for (int i = AS400.systemList.size() - 1; i >= 0; i--)
            {
                String elementName = (String)((Object[])AS400.systemList.elementAt(i))[0];
                if (systemName.equalsIgnoreCase(elementName))
                    AS400.systemList.removeElementAt(i);
                else if (isLocalHost && "localhost".equalsIgnoreCase(elementName))
                    AS400.systemList.removeElementAt(i);
                else if ((longName != null) && longName.equalsIgnoreCase(elementName))
                    AS400.systemList.removeElementAt(i);
            }
        }
    }

    /**
     * Connects to a service. Security is validated and a connection is established.
     * <p>
     * Services typically connect implicitly; therefore, this method does not have to be called to use a service. This
     * method can be used to control when the connection is established.
     * 
     * @param service The name of the service. Valid services are:
     *                <ul>
     *                <li>{@link #FILE FILE} - IFS file classes.
     *                <li>{@link #PRINT PRINT} - print classes.
     *                <li>{@link #COMMAND COMMAND} - command and program call classes.
     *                <li>{@link #DATAQUEUE DATAQUEUE} - data queue classes.
     *                <li>{@link #DATABASE DATABASE} - JDBC classes.
     *                <li>{@link #RECORDACCESS RECORDACCESS} - record level access classes.
     *                <li>{@link #CENTRAL CENTRAL} - license management classes.
     *                <li>{@link #SIGNON SIGNON} - sign-on classes.
     *                </ul>
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
   public void connectService(int service) throws AS400SecurityException, IOException {
       connectService(service, -1); 
   }
    
   /**
    * Connects to a service. Security is validated and a connection is established.
    * <p>
    * Services typically connect implicitly; therefore, this method does not have to be called to use a service. This
    * method can be used to control when the connection is established.
    * 
    * @param service      The name of the service. Valid services are:
    *                     <ul>
    *                     <li>{@link #FILE FILE} - IFS file classes.
    *                     <li>{@link #PRINT PRINT} - print classes.
    *                     <li>{@link #COMMAND COMMAND} - command and program call classes.
    *                     <li>{@link #DATAQUEUE DATAQUEUE} - data queue classes.
    *                     <li>{@link #DATABASE DATABASE} - JDBC classes.
    *                     <li>{@link #RECORDACCESS RECORDACCESS} - record level access classes.
    *                     <li>{@link #CENTRAL CENTRAL} - license management classes.
    *                     <li>{@link #SIGNON SIGNON} - sign-on classes.
    *                     </ul>
    * @param overridePort If non-negative, used to override the port to be used for the connection.
    * @exception AS400SecurityException If a security or authority error occurs.
    * @exception ExtendedIllegalArgumentException If service is not valid.
    * @exception IOException            If an error occurs while communicating with the system.
    **/
    public void connectService(int service, int overridePort) throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Connecting service:", service);
        
        // Validate parameter. HOSTCNN connection is for internal use, do not allow explicit connect requests. 
        if (service < 0 || service > 7)
            throw new ExtendedIllegalArgumentException("service (" + service + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);

        chooseImpl();

        // Before the thread to connect server, block the thread to refresh profile token credential.
        if (credVault_ instanceof ProfileTokenVault)
        {
            if (Trace.traceOn_) Trace.log(Trace.INFORMATION, "Before service connected, block the thread of refreshing profile token credential");
            ((ProfileTokenVault) credVault_).preventRefresh();
        }

        try {
            signon(service == AS400.SIGNON);
         
            impl_.connect(service, overridePort, skipSignonServer_);
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Service connected:", AS400.getServerName(service));
        }
        finally {
          // After the thread to connect server, notify the thread to refresh profile token credential.
            if (credVault_ instanceof ProfileTokenVault)
            {
                if (Trace.traceOn_) Trace.log(Trace.INFORMATION, "After service connected, notify the thread of refreshing profile token credential");
                ((ProfileTokenVault) credVault_).allowRefresh();
            }
        }
    }

    // Common code for all the constuctors and readObject.
    private void construct()
    {
        // See if we are running on IBM i.
        if (AS400.onAS400)
        {
            // OK, we are running on IBM i.
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Running on IBM i.");
            // Running on IBM i, don't prompt.
            guiAvailable_ = false;
        }
        
        if (isSecure())
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing secure AS400 object.");

            useSSLConnection_ = new SSLOptions();

            // Check for proxy encryption mode system property, if not set or not valid retain default of 3.
            String prop = SystemProperties.getProperty(SystemProperties.SECUREAS400_PROXY_ENCRYPTION_MODE);
            if (prop != null && (prop.equals("1") || prop.equals("2")))
                useSSLConnection_.proxyEncryptionMode_ = Integer.parseInt(prop);
        }
    }

    /**
     * Disconnects all services. All socket connections associated with this object will be closed. The signon
     * information is not changed, and connection properties remain frozen.
     * 
     * @see #resetAllServices
     **/
    public void disconnectAllServices()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Disconnecting all services...");
        if (impl_ != null)
        {
            impl_.disconnect(AS400.FILE);
            impl_.disconnect(AS400.PRINT);
            impl_.disconnect(AS400.COMMAND);
            impl_.disconnect(AS400.DATAQUEUE);
            impl_.disconnect(AS400.DATABASE);
            impl_.disconnect(AS400.RECORDACCESS);
            impl_.disconnect(AS400.CENTRAL);
            impl_.disconnect(AS400.SIGNON);
            // Note that HOSTCNN is never disconnected here unless explicitly disconnected.
        }
                
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "All services disconnected.");
    }

    /**
     * Disconnects the service. All socket connections associated with this service and this object will be closed.
     * 
     * @param service The name of the service. Valid services are:
     *                <ul>
     *                <li>{@link #FILE FILE} - IFS file classes.
     *                <li>{@link #PRINT PRINT} - print classes.
     *                <li>{@link #COMMAND COMMAND} - command and program call classes.
     *                <li>{@link #DATAQUEUE DATAQUEUE} - data queue classes.
     *                <li>{@link #DATABASE DATABASE} - JDBC classes.
     *                <li>{@link #RECORDACCESS RECORDACCESS} - record level access classes.
     *                <li>{@link #CENTRAL CENTRAL} - license management classes.
     *                <li>{@link #SIGNON SIGNON} - sign-on classes.
     *                <li>{@link #HOSTCNN HOSTCNN} - host-connection classes.
     *                </ul>
     * @exception ExtendedIllegalArgumentException If service is not valid.
     **/
    public void disconnectService(int service)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Disconnecting service:", service);
        
        // Validate parameter. Will allow users to disconnect HOSTCNN. 
        if (service < 0 || service > 8)
            throw new ExtendedIllegalArgumentException("service (" + service + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);

        if (impl_ == null) return;
        impl_.disconnect(service);
    }

    // Fire connect events here so source is public object.
    private void fireConnectEvent(ConnectionEvent event, boolean connect)
    {
        // If we have made it this far, we know we have listeners.
        event.setSource(this);

        Vector targets = (Vector)connectionListeners_.clone();
        for (int i = 0; i < targets.size(); ++i)
        {
            ConnectionListener target = (ConnectionListener)targets.elementAt(i);
            if (connect)
                target.connected(event);
            else
                target.disconnected(event);
        }
    }

    /**
     * Generates a profile token on behalf of the provided user identity. This user identity must be associated with a
     * user profile via EIM.
     * <p>
     * Invoking this method does not change the user ID and password assigned to the system or otherwise modify the user
     * or authorities under which the application is running. The profile associated with this system object must have
     * enough authority to generate an authentication token for another user.
     * <p>
     * This function is only supported on i5/OS V5R3M0 or greater.
     * 
     * @param userIdentity    The LDAP distinguished name.
     * @param tokenType       The type of profile token to create. Possible types are defined as fields on the
     *                        ProfileTokenCredential class:
     *                        <ul>
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_SINGLE_USE
     *                        TYPE_SINGLE_USE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_NON_RENEWABLE
     *                        TYPE_MULTIPLE_USE_NON_RENEWABLE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_RENEWABLE
     *                        TYPE_MULTIPLE_USE_RENEWABLE}
     *                        </ul>
     * @param timeoutInterval The number of seconds to expiration when the token is created (1-3600).
     * @return A ProfileTokenCredential representing the provided user identity.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public ProfileTokenCredential generateProfileToken(String userIdentity, int tokenType, int timeoutInterval) throws AS400SecurityException, IOException {
        return generateProfileToken(userIdentity, tokenType, timeoutInterval, null, null);
    }

    /**
     * Generates a profile token on behalf of the provided user identity. This user identity must be associated with a
     * user profile via EIM.
     * <p>
     * Invoking this method does not change the user ID and password assigned to the system or otherwise modify the user
     * or authorities under which the application is running. The profile associated with this system object must have
     * enough authority to generate an authentication token for another user.
     * <p>
     * This function is only supported on i5/OS V5R3M0 or greater.
     * 
     * @param userIdentity    The LDAP distinguished name.
     * @param tokenType       The type of profile token to create. Possible types are defined as fields on the
     *                        ProfileTokenCredential class:
     *                        <ul>
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_SINGLE_USE
     *                        TYPE_SINGLE_USE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_NON_RENEWABLE
     *                        TYPE_MULTIPLE_USE_NON_RENEWABLE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_RENEWABLE
     *                        TYPE_MULTIPLE_USE_RENEWABLE}
     *                        </ul>
     * @param timeoutInterval The number of seconds to expiration when the token is created (1-3600).
     * @param verificationID       The verification ID that will be associated with profile token. The verification ID
     *                             is the label that identifies the specific application, service, or action associated
     *                             with the profile token request. A null value will result in the usage of the
     *                             default value of QIBM_OS400_JT400.
     * @param remoteIPAddress      The remote IP address (the IP address of the requester) that will be associated with
     *                             profile token. A null value will result in the usage of the local IP address returned
     *                             on the connection to the host server, assuming that the profile token is being created
     *                             by the host server.  If the profile token is being created by an ILE API, the remoteIPAddress
     *                             will be null.
     * 
     * @return A ProfileTokenCredential representing the provided user identity.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public ProfileTokenCredential generateProfileToken(String userIdentity, int tokenType, int timeoutInterval, String verificationID, String remoteIPAddress) throws AS400SecurityException, IOException
    {
        connectService(AS400.SIGNON);

        if (userIdentity == null)
            throw new NullPointerException("userIdentity");

        ProfileTokenCredential profileToken = new ProfileTokenCredential();
        try
        {
            profileToken.setSystem(this);
            profileToken.setTokenType(tokenType);
            profileToken.setTimeoutInterval(timeoutInterval);
            profileToken.setVerificationID(verificationID);
            profileToken.setRemoteIPAddress(remoteIPAddress);
        }
        catch (PropertyVetoException e)
        {
            Trace.log(Trace.ERROR, "Unexpected PropertyVetoException:", e);
            throw new InternalErrorException(InternalErrorException.UNEXPECTED_EXCEPTION, e);
        }

        chooseImpl();
        synchronized (this) {
            impl_.generateProfileToken(profileToken, userIdentity);
        }
        
        return profileToken;
    }

    /**
     * Generates a VRM from a version, release, and modification. This can then be used to compare against the VRM
     * returned by getVRM().
     * 
     * @param version      The version.
     * @param release      The release.
     * @param modification The modification level.
     * @return The generated VRM.
     * @exception ExtendedIllegalArgumentException If version, release, or modification is not valid. 
     **/
    public static int generateVRM(int version, int release, int modification)
    {
        // Check for valid input.
        if (version < 0 || version > 0xFFFF)
            throw new ExtendedIllegalArgumentException("version (" + version + ")", ExtendedIllegalArgumentException.RANGE_NOT_VALID);

        if (release < 0 || release > 0xFF)
            throw new ExtendedIllegalArgumentException("release (" + release + ")", ExtendedIllegalArgumentException.RANGE_NOT_VALID);

        if (modification < 0 || modification > 0xFF)
            throw new ExtendedIllegalArgumentException("modification (" + modification + ")", ExtendedIllegalArgumentException.RANGE_NOT_VALID);

        return (version << 16) + (release << 8)  + modification;
    }

    /**
     * Returns the authentication scheme for this object. By default this object starts in password mode. This value may
     * not be correct before a connection to the system has been made. Valid authentication schemes are:
     * <ul>
     * <li>{@link #AUTHENTICATION_SCHEME_PASSWORD AUTHENTICATION_SCHEME_PASSWORD} - passwords are used.
     * <li>{@link #AUTHENTICATION_SCHEME_GSS_TOKEN AUTHENTICATION_SCHEME_GSS_TOKEN} - GSS tokens are used.
     * <li>{@link #AUTHENTICATION_SCHEME_PROFILE_TOKEN AUTHENTICATION_SCHEME_PROFILE_TOKEN} - profile tokens are used.
     * <li>{@link #AUTHENTICATION_SCHEME_IDENTITY_TOKEN AUTHENTICATION_SCHEME_IDENTITY_TOKEN} - identity tokens are
     * used.
     * </ul>
     * 
     * @return The authentication scheme in use for this object.
     **/
    public int getAuthenticationScheme()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting authentication scheme, scheme:", credVault_.getType());
        return credVault_.getType();
    }

    /**
     * Returns the CCSID for this object. The CCSID returned either is the one retrieved based on the user profile or is
     * set by the setCcsid() method.
     * 
     * @return The CCSID in use for this object.
     **/
    public int getCcsid()
    {
        if (ccsid_ == 0)
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting CCSID.");
            try
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Retrieving CCSID from system...");
                chooseImpl();
                signon(false);
                ccsid_ = signonInfo_.serverCCSID;
            }
            catch (Exception e)
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Taking best guess CCSID:", e);
                ccsid_ = ExecutionEnvironment.getBestGuessAS400Ccsid();
            }
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "CCSID:", ccsid_);
        }
        
        return ccsid_;
    }

  // Calculate number of days until user's password expires.
  private int getDaysToExpiration()
  {
      if (signonInfo_ != null) 
      {
          GregorianCalendar expirationDate = signonInfo_.expirationDate;
          GregorianCalendar now = signonInfo_.currentSignonDate;
          if (expirationDate != null && now != null)
          {
              long lExpiration = expirationDate.getTimeInMillis();
              long lNow = now.getTimeInMillis();

              // Divide by number of seconds in day, round up.
              int days = (int) (((lExpiration - lNow) / 0x5265C00) + 1);

              return days;
          }
      }
      
      // No expiration date.
      return 365;
  }

  /**
   * Returns the default sign-on handler. If none has been specified, returns an instance of the Toolbox's internal
   * sign-on handler.
   * 
   * @return The default sign-on handler. Never returns null.
   * @see #setDefaultSignonHandler
   **/
    public static SignonHandler getDefaultSignonHandler()
    {
        if (defaultSignonHandler_ != null) return defaultSignonHandler_;
        try {
            return (SignonHandler)defaultSignonHandlerClass_.newInstance();
        }
        catch (Exception e)
        {
            Trace.log(Trace.ERROR, "Unable to cast specified default sign-on handler to a SignonHandler: " + defaultSignonHandlerClass_.getName(), e);
            return new ToolboxSignonHandler();
        }
    }

    /**
     * Returns the relational database name (RDB name) used for record-level access (DDM) connections. The RDB name
     * corresponds to the independent auxiliary storage pool (IASP) that is being used.
     * 
     * @return The name of the IASP or RDB that is in use by this object's RECORDACCESS service, or null if the IASP
     *         used will be the default system pool (*SYSBAS).
     * @see #setDDMRDB
     **/
    public String getDDMRDB()
    {
        return ddmRDB_;
    }

    /**
     * Returns the default user ID for this system name. This user ID is used to connect if a user ID was not used to
     * construct the object.
     * 
     * @param systemName The name of the IBM i system.
     * @return The default user ID for this system. A null is returned if there is not a default user.
     **/
    public static String getDefaultUser(String systemName)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting the default user, system name:", systemName);
        if (systemName == null)
            throw new NullPointerException("systemName");

        String defaultUser = (String)AS400.defaultUsers.get(resolveSystem(systemName));
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Default user:", defaultUser);
        return defaultUser;
    }

    /**
     * Returns the GSS name string. This method will only return the information provided on the setGSSName() method.
     * 
     * @return The GSS name string, or an empty string ("") if not set.
     **/
    public synchronized String getGSSName()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting GSS name:", gssName_);
        return gssName_;
    }

    /**
     * Returns the option for how the JGSS framework will be used.
     * 
     * @return A constant indicating how the JGSS framework will be used. Valid values are:
     *         <ul>
     *         <li>{@link #GSS_OPTION_MANDATORY GSS_OPTION_MANDATORY}
     *         <li>{@link #GSS_OPTION_FALLBACK GSS_OPTION_FALLBACK}
     *         <li>{@link #GSS_OPTION_NONE GSS_OPTION_NONE}
     *         </ul>
     **/
    public int getGSSOption()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting GSS option:", gssOption_);
        return gssOption_;
    }

    /**
     * Get underlying AS400Impl object. Should only be used by code internal to the driver.
     * 
     * @return underlying AS400Impl object
     */
    public AS400Impl getImpl()
    {
        chooseImpl();
        return impl_;
    }

    // Returns the job CCSID.
    int getJobCcsid() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting job CCSID.");
        chooseImpl();
        signon(false);
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Job CCSID:", signonInfo_.serverCCSID);
        return signonInfo_.serverCCSID;
    }

    /**
     * Returns the encoding that corresponds to the job CCSID.
     * 
     * @return The encoding.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     **/
    public String getJobCCSIDEncoding() throws AS400SecurityException, IOException, InterruptedException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting job CCSID encoding.");
        if (signonInfo_ == null) {
            chooseImpl();
            signon(false);
        }
        int ccsid = signonInfo_.serverCCSID;
        String encoding = impl_.ccsidToEncoding(ccsid);
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Job CCSID encoding:", encoding);
        return encoding;
    }

    /**
     * Returns an array of Job objects representing the jobs to which this object is connected. This information is only
     * available when connecting to i5/OS V5R2M0 and later systems. The array will be of length zero if no connections
     * are currently active.
     * 
     * @param service The name of the service. Valid services are:
     *                <ul>
     *                <li>{@link #FILE FILE} - IFS file classes.
     *                <li>{@link #PRINT PRINT} - print classes.
     *                <li>{@link #COMMAND COMMAND} - command and program call classes.
     *                <li>{@link #DATAQUEUE DATAQUEUE} - data queue classes.
     *                <li>{@link #DATABASE DATABASE} - JDBC classes.
     *                <li>{@link #RECORDACCESS RECORDACCESS} - record level access classes.
     *                <li>{@link #CENTRAL CENTRAL} - license management classes.
     *                <li>{@link #SIGNON SIGNON} - sign-on classes.
     *                <li>{@link #HOSTCNN HOSTCNN} - host-connection classes.
     *                </ul>
     * @return The array of job objects.
     * @exception ExtendedIllegalArgumentException If service is not valid.
     **/
    public Job[] getJobs(int service)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting jobs, service:", service);
        // Validate parameter.
        if (service < 0 || service > 8)
        {
            Trace.log(Trace.ERROR, "Value of parameter 'service' is not valid:", service);
            throw new ExtendedIllegalArgumentException("service (" + service + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);
        }

        if (impl_ == null) return new Job[0];
        String[] jobStrings = impl_.getJobs(service);
        Job[] jobs = new Job[jobStrings.length];
        for (int i = 0; i < jobStrings.length; ++i)
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Constructing Job for job:", jobStrings[i]);
            if (jobStrings[i] == null || jobStrings[i].length() == 0) return new Job[0];
            StringTokenizer tokenizer = new StringTokenizer(jobStrings[i], "/");
            String jobNumber = tokenizer.nextToken();
            String jobUser = tokenizer.nextToken();
            String jobName = tokenizer.nextToken();

            jobs[i] = new Job(this, jobName, jobUser, jobNumber);
        }
        
        return jobs;
    }

    /**
     * Returns the Locale associated with this system object. The Locale may have been set with the setLocale() method,
     * or it may be the default Locale for the client environment. Unless specifically overridden, this Locale is used
     * to set the National Language Version (NLV) on the system. Only the COMMAND, PRINT, and DATABASE services accept
     * an NLV.
     * 
     * @return The Locale object.
     **/
    public Locale getLocale()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting locale: " + locale_);
        return locale_;
    }

    /**
     * Returns the modification level of the IBM i system.
     * <p>
     * A connection is required to the system to retrieve this information. If a connection has not been established,
     * one is created to retrieve the information.
     * 
     * @return The modification level. For example, version 5, release 1, modification level 0 returns 0.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public int getModification() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting modification level.");
        if (signonInfo_ == null) {
            chooseImpl();
            signon(false);
        }
        int modification = signonInfo_.version.getModificationLevel();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Modification level:", modification);

        return modification;
    }

    /**
     * Returns the National Language Version (NLV) that will be sent to the system. Only the COMMAND, PRINT, and
     * DATABASE services accept an NLV.
     * 
     * @return The NLV.
     **/
    public String getNLV()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting NLV:", nlv_);
        return nlv_;
    }

    /**
     * Returns the password expiration date for the signed-on user. If the profile's password expiration interval is set
     * to *NOMAX, null is returned.
     * <p>
     * A connection is required to retrieve this information. If a connection has not been established, one is created
     * to retrieve the information.
     * 
     * @return The password expiration date. If the profile has no password expiration data (*NOMAX), null is returned.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public GregorianCalendar getPasswordExpirationDate() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting password expiration date.");
        if (signonInfo_ == null) {
            chooseImpl();
            signon(false);
        }
        GregorianCalendar expire = signonInfo_.expirationDate;
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Password expiration date: " + expire);

        return (expire == null) ? null : (GregorianCalendar)expire.clone();
    }
    
    /**
     * Set a flag to indicate whether or not to use the password expiration warning days from the QPWDEXPWRN system
     * value. This capability is supported with V6R1M0 and later systems with V6R1M0 5761SS1 PTF SI48808 or V7R1M0
     * 5770SS1 PTF SI48809.
     * 
     * @param useSystem indicates whether or not to use password expiration warning days from the QPWDEXPWRN system
     *                  value
     */
    public void setUseSystemPasswordExpirationWarningDays(boolean useSystem)
    {
        if (useSystem) {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Use system password expiration(QPWDEXPWRN) warning.");
            useSystemExpirationWarning_ = true;
        } else {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Don't use system password expiration(QPWDEXPWRN) warning.");
            useSystemExpirationWarning_ = false;
        }
    }
   
    /**
     * Returns the number of days before password expiration to start warning the user based on the value of the
     * QPWDEXPWRN system value. This capability is supported with V6R1M0 and later systems with V6R1M0 5761SS1 PTF
     * SI48808 or V7R1M0 5770SS1 PTF SI48809.
     * 
     * @return The number of days before password expiration to start warning the user. If
     *         {@link #setUseSystemPasswordExpirationWarningDays} is enabled and supported, return the value of the
     *         QPWDEXPWRN system value. Otherwise, return {@link #getPasswordExpirationWarningDays}.
     * @throws AS400SecurityException If a security or authority error occurs.
     * @throws IOException            If an error occurs while communicating with the system.
     */
    public int getSystemPasswordExpirationWarningDays()throws AS400SecurityException, IOException
    {
        if (useSystemExpirationWarning_)
        {
            if (signonInfo_ == null) {
              chooseImpl();
              signon(false);
            }
            
            if (signonInfo_.PWDexpirationWarning > 0) {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Use system password expiration(QPWDEXPWRN) warning: " + signonInfo_.PWDexpirationWarning);
                return signonInfo_.PWDexpirationWarning;
            }
        }

        return getPasswordExpirationWarningDays();
    }
   
    /**
     * Determines if the password expiration date for the user profile is within the password expiration warning days
     * for the system returned by {@link #getPasswordExpirationDays()}.
     * 
     * @return true if the password expiration date for the user profile is within the password expiration days;
     *         otherwise, return false
     * @throws AS400SecurityException If a security or authority error occurs.
     * @throws IOException            If an error occurs while communicating with the system.
     */
    public boolean isInPasswordExpirationWarningDays() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if within the password expiration warning days for the system. " );

        chooseImpl();
        signon(false);

        boolean warn = getDaysToExpiration() <= getSystemPasswordExpirationWarningDays();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "In password expiration warning days: " + warn);

        return warn;
    }

    /**
     * Returns the number of days until the user profile's password expires.
     * <p>
     * A connection is required to retrieve this information. If a connection has not been established, one is created
     * to retrieve the information.
     * 
     * @return The number of days until the user profiles' password expires.
     * @throws AS400SecurityException If a security or authority error occurs.
     * @throws IOException            If an error occurs while communicating with the system.
     */
    public int getPasswordExpirationDays() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting password expiration warning days.");
        
        chooseImpl();
        signon(false);
        
        int days = getDaysToExpiration();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Password expiration days: " + days);

        return days;
    }
    
    /**
     * Returns the number of days before password expiration to start warning the user.
     * 
     * @return The number of days before expiration to warn the user.
     **/
    public static int getPasswordExpirationWarningDays()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting the password expiration warning days:", AS400.expirationWarning);
        return AS400.expirationWarning;
    }

    /**
     * Returns the date of the last successful sign-on.
     * <p>
     * A connection is required to retrieve this information. If a connection has not been established, one is created
     * to retrieve the information.
     * 
     * @return The date of the last successful sign-on.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public GregorianCalendar getPreviousSignonDate() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting previous signon date.");
        if (signonInfo_ == null) {
            chooseImpl();
            signon(false);
        }
        GregorianCalendar last = signonInfo_.lastSignonDate;
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Previous signon date: " + last);

        return (last == null) ? null : (GregorianCalendar)last.clone();
    }

    /**
     * Returns a profile token representing the signed-on user profile.
     * <p>
     * The returned token will be created single-use with a one hour time to expiration. Subsequent method calls will
     * return the same token, regardless of the token status.
     * <p>
     * This function is not supported if the assigned password is *CURRENT.
     * <p>
     * This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> If an additional authentication factor has been set for the AS400 object, it will be used when
     * generating the profile token.
     * 
     * @return A ProfileTokenCredential representing the currently signed on user.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     * @deprecated Use {@link #getProfileToken(int,int) getProfileToken(int,int)} instead.
     **/
    @Deprecated
    public ProfileTokenCredential getProfileToken() throws AS400SecurityException, IOException, InterruptedException
    {
        connectService(AS400.SIGNON);

        if (signonInfo_.profileToken == null)
            signonInfo_.profileToken = getProfileToken(ProfileTokenCredential.TYPE_SINGLE_USE, 3600);

        return (ProfileTokenCredential)signonInfo_.profileToken;
    }

    /**
     * Authenticates the assigned user profile and password and returns a corresponding ProfileTokenCredential if
     * successful.
     * <p>
     * This function is not supported if the assigned password is *CURRENT and cannot be used to generate a renewable
     * token. This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> If an additional authentication factor has been set for the AS400 object, it will be used when
     * generating the profile token.
     * 
     * @param tokenType       The type of profile token to create. Possible types are defined as fields on the
     *                        ProfileTokenCredential class:
     *                        <ul>
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_SINGLE_USE
     *                        TYPE_SINGLE_USE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_NON_RENEWABLE
     *                        TYPE_MULTIPLE_USE_NON_RENEWABLE}
     *                        </ul>
     * @param timeoutInterval The number of seconds to expiration when the token is created (1-3600).
     * @return A ProfileTokenCredential representing the signed-on user.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     **/
    public ProfileTokenCredential getProfileToken(int tokenType, int timeoutInterval) throws AS400SecurityException, IOException, InterruptedException {
        return getProfileToken(tokenType, timeoutInterval, null, null);
    }

    /**
     * Authenticates the assigned user profile and password and returns a corresponding ProfileTokenCredential if
     * successful.
     * <p>
     * This function is not supported if the assigned password is *CURRENT and cannot be used to generate a renewable
     * token. This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> If an additional authentication factor has been set for the AS400 object, it will be used when
     * generating the profile token.
     * 
     * @param tokenType       The type of profile token to create. Possible types are defined as fields on the
     *                        ProfileTokenCredential class:
     *                        <ul>
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_SINGLE_USE
     *                        TYPE_SINGLE_USE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_NON_RENEWABLE
     *                        TYPE_MULTIPLE_USE_NON_RENEWABLE}
     *                        </ul>
     * @param timeoutInterval The number of seconds to expiration when the token is created (1-3600).
     * @param verificationID       The verification ID that will be associated with profile token. The verification ID
     *                             is the label that identifies the specific application, service, or action associated
     *                             with the profile token request. A null value will result in the usage of the
     *                             default value of QIBM_OS400_JT400.
     * @param remoteIPAddress      The remote IP address (the IP address of the requester) that will be associated with
     *                             profile token. A null value will result in the usage of the local IP address returned
     *                             on the connection to the host server, assuming that the profile token is being created
     *                             by the host server.  If the profile token is being created by an ILE API, the remoteIPAddress
     *                             will be null.
     * @return A ProfileTokenCredential representing the signed-on user.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     **/
    public ProfileTokenCredential getProfileToken(int tokenType, int timeoutInterval, String verificationID, String remoteIPAddress) throws AS400SecurityException, IOException, InterruptedException
    {
        connectService(AS400.SIGNON);

        // If the password is not set and we are not using Kerberos.
        if (credVault_.isEmpty() && credVault_.getType() != AUTHENTICATION_SCHEME_GSS_TOKEN)
            throw new AS400SecurityException(AS400SecurityException.PASSWORD_NOT_SET);
        
        if (tokenType == ProfileTokenCredential.TYPE_MULTIPLE_USE_RENEWABLE)
        {
            Trace.log(Trace.ERROR, "Request not supported for renewable token type.");
            throw new AS400SecurityException(AS400SecurityException.REQUEST_NOT_SUPPORTED);
        }

        ProfileTokenCredential profileToken = new ProfileTokenCredential();
        try
        {
            profileToken.setSystem(this);
            profileToken.setTokenType(tokenType);
            profileToken.setTimeoutInterval(timeoutInterval);
            profileToken.setVerificationID(verificationID);
            profileToken.setRemoteIPAddress(remoteIPAddress);
        }
        catch (PropertyVetoException e)
        {
            Trace.log(Trace.ERROR, "Unexpected PropertyVetoException:", e);
            throw new InternalErrorException(InternalErrorException.UNEXPECTED_EXCEPTION, e);
        }

        byte[] proxySeed = new byte[9];
        CredentialVault.rng.nextBytes(proxySeed);
        synchronized (this)
        {
            // The 'impl' needs our credential to authenticate with the system
            // (i.e. to make sure we have enough authority to generate the profile token).
            // Note that we do not send across the bytes in the clear, but encode them
            // using random seeds generated and exchanged with the 'impl' object.
            // Also, Don't re-encode our own vault; we might need to reuse it later.

            CredentialVault tempVault = (CredentialVault)credVault_.clone();
            tempVault.storeEncodedUsingExternalSeeds(proxySeed, impl_.exchangeSeed(proxySeed));
            impl_.generateProfileToken(profileToken, userId_, tempVault, additionalAuthenticationFactor_, gssName_);
        }
        
        return profileToken;
    }
    
    /**
     * Authenticates the given user profile and password and returns a corresponding ProfileTokenCredential if
     * successful.
     * <p>
     * Invoking this method does not change the user ID and password assigned to the system or otherwise modify the user
     * or authorities under which the application is running.
     * <p>
     * This method generates a single use token with a timeout of one hour.
     * <p>
     * This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> Providing an incorrect password increments the number of failed sign-on attempts for the user
     * profile, and can result in the profile being disabled. Refer to documentation on the
     * <i>ProfileTokenCredential</i> class for additional restrictions.
     * 
     * @param userId   The user profile name.
     * @param password The user profile password.
     * @return A ProfileTokenCredential representing the authenticated profile and password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     * @deprecated Use {@link #getProfileToken(String, char[]) getProfileToken(String, char[])} instead.
     **/
    @Deprecated
    public ProfileTokenCredential getProfileToken(String userId, String password) throws AS400SecurityException, IOException, InterruptedException {
        return getProfileToken(userId, password, ProfileTokenCredential.TYPE_SINGLE_USE, 3600);
    }

    /**
     * Authenticates the given user profile and password and returns a corresponding ProfileTokenCredential if
     * successful.
     * <p>
     * Invoking this method does not change the user ID and password assigned to the system or otherwise modify the user
     * or authorities under which the application is running.
     * <p>
     * This method generates a single use token with a timeout of one hour.
     * <p>
     * This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> Providing an incorrect password increments the number of failed sign-on attempts for the user
     * profile, and can result in the profile being disabled. Refer to documentation on the
     * <i>ProfileTokenCredential</i> class for additional restrictions.
     * 
     * @param userId   The user profile name.
     * @param password The user profile password.
     * @return A ProfileTokenCredential representing the authenticated profile and password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     **/
    public ProfileTokenCredential getProfileToken(String userId, char[] password) throws AS400SecurityException, IOException, InterruptedException {
        return getProfileToken(userId, password, ProfileTokenCredential.TYPE_SINGLE_USE, 3600);
    }

    /**
     * Authenticates the given user profile and password and returns a corresponding ProfileTokenCredential if
     * successful.
     * <p>
     * Invoking this method does not change the user ID and password assigned to the system or otherwise modify the user
     * or authorities under which the application is running.
     * <p>
     * This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> Providing an incorrect password increments the number of failed sign-on attempts for the user
     * profile, and can result in the profile being disabled. Refer to documentation on the
     * <i>ProfileTokenCredential</i> class for additional restrictions.
     * 
     * @param userId          The user profile name.
     * @param password        The user profile password.
     * @param tokenType       The type of profile token to create. Possible types are defined as fields on the
     *                        ProfileTokenCredential class:
     *                        <ul>
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_SINGLE_USE
     *                        TYPE_SINGLE_USE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_NON_RENEWABLE
     *                        TYPE_MULTIPLE_USE_NON_RENEWABLE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_RENEWABLE
     *                        TYPE_MULTIPLE_USE_RENEWABLE}
     *                        </ul>
     * @param timeoutInterval The number of seconds to expiration when the token is created (1-3600).
     * @return A ProfileTokenCredential representing the authenticated profile and password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     * @deprecated Use {@link #getProfileToken(String, char[],int,int) getProfileToken(String, char[],int,int)} instead.
     **/
    @Deprecated
    public ProfileTokenCredential getProfileToken(String userId, String password, int tokenType, int timeoutInterval) throws AS400SecurityException, IOException, InterruptedException
    {
        char[] passwordChars = (password == null) ? null : password.toCharArray();
        
        try 
        {
            ProfileTokenCredential pt =  getProfileToken(userId, passwordChars, tokenType, timeoutInterval);
            return pt;
        }
        finally {
            PasswordVault.clearArray(passwordChars);
        }
    }

    /**
     * Authenticates the given user profile and password and returns a corresponding ProfileTokenCredential if
     * successful.
     * <p>
     * Invoking this method does not change the user ID and password assigned to the system or otherwise modify the user
     * or authorities under which the application is running.
     * <p>
     * This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> Providing an incorrect password increments the number of failed sign-on attempts for the user
     * profile, and can result in the profile being disabled. Refer to documentation on the
     * <i>ProfileTokenCredential</i> class for additional restrictions.
     * 
     * @param userId          The user profile name.
     * @param password        The user profile password.
     * @param tokenType       The type of profile token to create. Possible types are defined as fields on the
     *                        ProfileTokenCredential class:
     *                        <ul>
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_SINGLE_USE
     *                        TYPE_SINGLE_USE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_NON_RENEWABLE
     *                        TYPE_MULTIPLE_USE_NON_RENEWABLE}
     *                        <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_RENEWABLE
     *                        TYPE_MULTIPLE_USE_RENEWABLE}
     *                        </ul>
     * @param timeoutInterval The number of seconds to expiration when the token is created (1-3600).
     * @return A ProfileTokenCredential representing the authenticated profile and password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     **/
    public ProfileTokenCredential getProfileToken(String userId, char[] password, int tokenType, int timeoutInterval) throws AS400SecurityException, IOException, InterruptedException
    {
        return getProfileToken(userId, password, null, tokenType, timeoutInterval, null);
    }
    
    /**
     * Authenticates the given user profile and password and returns a corresponding ProfileTokenCredential if
     * successful.
     * <p>
     * Invoking this method does not change the user ID and password assigned to the system or otherwise modify the user
     * or authorities under which the application is running.
     * <p>
     * This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> Providing an incorrect password increments the number of failed sign-on attempts for the user
     * profile, and can result in the profile being disabled. Refer to documentation on the
     * <i>ProfileTokenCredential</i> class for additional restrictions.
     * 
     * @param userId               The user profile name.
     * @param password             The user profile password.
     * @param additionalAuthFactor The additional authentication factor or null if not specifying one.
     * @param tokenType            The type of profile token to create. Possible types are defined as fields on the
     *                             ProfileTokenCredential class:
     *                             <ul>
     *                             <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_SINGLE_USE
     *                             TYPE_SINGLE_USE}
     *                             <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_NON_RENEWABLE
     *                             TYPE_MULTIPLE_USE_NON_RENEWABLE}
     *                             <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_RENEWABLE
     *                             TYPE_MULTIPLE_USE_RENEWABLE}
     *                             </ul>
     * @param timeoutInterval      The number of seconds to expiration when the token is created (1-3600).
     * @param verificationID       The verification ID that will be associated with profile token. The verification ID
     *                             is the label that identifies the specific application, service, or action associated
     *                             with the profile token request. A null value will result in the usage of the
     *                             default value of QIBM_OS400_JT400.
     * @param remoteIPAddress      The remote IP address (the IP address of the requester) that will be associated with
     *                             profile token. AA null value will result in the usage of the local IP address returned
     *                             on the connection to the host server, assuming that the profile token is being created
     *                             by the host server.  If the profile token is being created by an ILE API, the remoteIPAddress
     *                             will be null.
     * @return A ProfileTokenCredential representing the authenticated profile and password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     **/
    public ProfileTokenCredential getProfileToken(String userId, char[] password, char[] additionalAuthFactor, int tokenType, int timeoutInterval, 
                                                  String verificationID, String remoteIPAddress) throws AS400SecurityException, IOException, InterruptedException
    {
    	ProfileTokenEnhancedInfo enhancedInfo = new ProfileTokenEnhancedInfo(); 
        enhancedInfo.setVerificationID(verificationID);
        enhancedInfo.setRemoteIPAddress(remoteIPAddress);
        return getProfileToken(userId,password,additionalAuthFactor,tokenType, timeoutInterval, enhancedInfo);
    }

    /**
     * Authenticates the given user profile and password and returns a corresponding ProfileTokenCredential if
     * successful.
     * <p>
     * Invoking this method does not change the user ID and password assigned to the system or otherwise modify the user
     * or authorities under which the application is running.
     * <p>
     * This function is only supported if the system is at i5/OS V4R5M0 or greater.
     * <p>
     * <b>Note:</b> Providing an incorrect password increments the number of failed sign-on attempts for the user
     * profile, and can result in the profile being disabled. Refer to documentation on the
     * <i>ProfileTokenCredential</i> class for additional restrictions.
     * 
     * @param userId               The user profile name.
     * @param password             The user profile password.
     * @param additionalAuthFactor The additional authentication factor or null if not specifying one.
     * @param tokenType            The type of profile token to create. Possible types are defined as fields on the
     *                             ProfileTokenCredential class:
     *                             <ul>
     *                             <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_SINGLE_USE
     *                             TYPE_SINGLE_USE}
     *                             <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_NON_RENEWABLE
     *                             TYPE_MULTIPLE_USE_NON_RENEWABLE}
     *                             <li>{@link com.ibm.as400.security.auth.ProfileTokenCredential#TYPE_MULTIPLE_USE_RENEWABLE
     *                             TYPE_MULTIPLE_USE_RENEWABLE}
     *                             </ul>
     * @param timeoutInterval      The number of seconds to expiration when the token is created (1-3600).
     * @param enhancedInfo         Information used for creating an enhanced profile token.
     * @return A ProfileTokenCredential representing the authenticated profile and password.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     * @exception IOException            If an error occurs while communicating with the system.
     * @exception InterruptedException   If this thread is interrupted.
     **/
    public ProfileTokenCredential getProfileToken(String userId, char[] password, char[] additionalAuthFactor, int tokenType, int timeoutInterval, 
                                                  ProfileTokenEnhancedInfo enhancedInfo ) throws AS400SecurityException, IOException, InterruptedException
    {
        connectService(AS400.SIGNON);

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
 
        if (enhancedInfo == null) { 
        	enhancedInfo = new ProfileTokenEnhancedInfo(); 
        }
        checkPasswordNullAndLength(password, "password");
      
        if (isTurkish()) {
          userId = userId.toUpperCase(Locale.ENGLISH);
          if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }
        
        userId = resolveUserId(userId.toUpperCase());

        ProfileTokenCredential profileToken = new ProfileTokenCredential();
        try
        {
            profileToken.setSystem(this);
            profileToken.setTokenType(tokenType);
            profileToken.setTimeoutInterval(timeoutInterval);
            profileToken.setEnhancedInfo(enhancedInfo); 
            
        }
        catch (PropertyVetoException e)
        {
            Trace.log(Trace.ERROR, "Unexpected PropertyVetoException:", e);
            throw new InternalErrorException(InternalErrorException.UNEXPECTED_EXCEPTION, e);
        }

        byte[] proxySeed = new byte[9];
        CredentialVault.rng.nextBytes(proxySeed);
        synchronized (this)
        {
            PasswordVault tempVault = new PasswordVault(password);
            tempVault.storeEncodedUsingExternalSeeds(proxySeed, impl_.exchangeSeed(proxySeed));
            impl_.generateProfileToken(profileToken, userId, tempVault, additionalAuthFactor, gssName_);
        }
        
        return profileToken;
    }

    /**
     * Returns the name of the middle-tier machine where the proxy server is running.
     * 
     * @return The name of the middle-tier machine where the proxy server is running, or an empty string ("") if not
     *         set.
     **/
    public String getProxyServer()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting proxy server:", proxyServer_);
        return proxyServer_;
    }

    /**
     * Returns the release of the IBM i system.
     * <p>
     * A connection is required to the system in order to retrieve this information. If a connection has not been
     * established, one is created to retrieve the system information.
     * 
     * @return The release of the IBM i system. For example, version 5, release 1, modification level 0, returns 1.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public int getRelease() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting release level.");
        if (signonInfo_ == null) {
            chooseImpl();
            signon(false);
        }
        int release = signonInfo_.version.getRelease();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Release level:", release);

        return release;
    }

    /**
     * Converts a service constant to the string representation of the service. For example, the integer AS400.File
     * corresponds to the string "as-file".
     * 
     * @param service The service represented by it's integer value.
     * @return The string representation of the service.
     * @exception ExtendedIllegalArgumentException If service is not valid.
     */
    public static String getServerName(int service)
    {
        switch (service)
        {
            case AS400.FILE:
                return "as-file";
            case AS400.PRINT:
                return "as-netprt";
            case AS400.COMMAND:
                return "as-rmtcmd";
            case AS400.DATAQUEUE:
                return"as-dtaq";
            case AS400.DATABASE:
                return "as-database";
            case AS400.RECORDACCESS:
                return "as-ddm";
            case AS400.CENTRAL:
                return "as-central";
            case AS400.HOSTCNN:
                return "as-hostcnn";
            case AS400.SIGNON:
                return "as-signon";
            default:
                throw new ExtendedIllegalArgumentException("service (" + service + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);
        }
    }

    /**
     * Returns the service port stored in the service port table for the specified service.
     * 
     * @param service The name of the service. Valid services are:
     *                <ul>
     *                <li>{@link #FILE FILE} - IFS file classes.
     *                <li>{@link #PRINT PRINT} - print classes.
     *                <li>{@link #COMMAND COMMAND} - command and program call classes.
     *                <li>{@link #DATAQUEUE DATAQUEUE} - data queue classes.
     *                <li>{@link #DATABASE DATABASE} - JDBC classes.
     *                <li>{@link #RECORDACCESS RECORDACCESS} - record level access classes.
     *                <li>{@link #CENTRAL CENTRAL} - license management classes.
     *                <li>{@link #SIGNON SIGNON} - sign-on classes.
     *                <li>{@link #HOSTCNN HOSTCNN} - host-connection classes.
     *                </ul>
     * @return The port specified in the service port table. The value {@link #USE_PORT_MAPPER USE_PORT_MAPPER} will be
     *         returned if the service has not been set, and the service has not been connected.
     * @exception ExtendedIllegalArgumentException If service is not valid.
     * @exception ExtendedIllegalStateException  If system name not set.
     **/
    public int getServicePort(int service)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting service port, service:", service);
        // Validate parameter.
        if (service < 0 || service > 8)
        {
            throw new ExtendedIllegalArgumentException("service (" + service + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);
        }

        // Validate state.
        if (systemName_.length() == 0 && !systemNameLocal_)
        {
            Trace.log(Trace.ERROR, "Cannot get service port before system name is set.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }

        chooseImpl();
        int port = impl_.getServicePort((systemNameLocal_) ? "localhost" : systemName_, service);
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Service port:", port);
        return port;
    }

    /**
     * Returns the date for the current sign-on.
     * <p>
     * A connection is required to the system to retrieve this information. If a connection has not been established,
     * one is created to retrieve the system information.
     * 
     * @return The date for the current sign-on.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public GregorianCalendar getSignonDate() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting signon date.");

        chooseImpl();
        signon(false);

        GregorianCalendar current = signonInfo_.currentSignonDate;
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Signon date: " + current);

        return (current == null) ? null : (GregorianCalendar)current.clone();
    }

    /**
     * Returns the sign-on handler that is used by this object. Never returns null.
     * 
     * @return The sign-on handler.
     * @see #setSignonHandler
     * @see #setDefaultSignonHandler
     **/
    public SignonHandler getSignonHandler()
    {
        return (signonHandler_ != null) ? signonHandler_ : getDefaultSignonHandler();
    }

    /**
     * Returns a copy of the socket options object.
     * 
     * @return The socket options object.
     **/
    public SocketProperties getSocketProperties()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting socket properties.");
        SocketProperties socketProperties = new SocketProperties();
        socketProperties.copyValues(socketProperties_);
        return socketProperties;
    }

    /**
     * Returns the name of the IBM i system. The system name is provided on the constructor or may have been provided by
     * the user at the sign-on prompt.
     * 
     * @return The name of the IBM i system, or an empty string ("") if not set.
     **/
    public String getSystemName()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting system name: " + systemName_ + ", is local:", systemNameLocal_);
        return (systemNameLocal_) ? "localhost" : systemName_;
    }

    /**
     * Returns the time zone of the IBM i system. The TimeZone object will have the correct UTC offset for the system.
     * 
     * @return A TimeZone object representing the time zone for the system.
     * @exception AS400SecurityException          If a security or authority error occurs.
     * @exception ErrorCompletingRequestException If an error occurs before the request is completed.
     * @exception InterruptedException            If this thread is interrupted.
     * @exception IOException                     If an error occurs while communicating with the system.
     * @exception ObjectDoesNotExistException     If the API used to retrieve the information does not exist on the
     *                                            system.
     * @see DateTimeConverter#timeZoneForSystem
     * @deprecated Use {@link #getTimeZone() getTimeZone()} instead.
     **/
    @Deprecated
    public TimeZone getSystemTimeZone()
      throws AS400SecurityException,
             ErrorCompletingRequestException,
             InterruptedException,
             IOException,
             ObjectDoesNotExistException
    {
        return getTimeZone();
    }

    /**
     * Returns the time zone of the IBM i system. The TimeZone object will have the correct UTC offset for the system.
     * 
     * @return A TimeZone object representing the time zone for the system.
     * @exception AS400SecurityException          If a security or authority error occurs.
     * @exception ErrorCompletingRequestException If an error occurs before the request is completed.
     * @exception InterruptedException            If this thread is interrupted.
     * @exception IOException                     If an error occurs while communicating with the system.
     * @exception ObjectDoesNotExistException     If the API used to retrieve the information does not exist on the
     *                                            system.
     * @see DateTimeConverter#timeZoneForSystem
     **/
    public TimeZone getTimeZone()
      throws AS400SecurityException,
             ErrorCompletingRequestException,
             InterruptedException,
             IOException,
             ObjectDoesNotExistException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting time zone for System");
        if (timezone_ == null)
          timezone_ = DateTimeConverter.timeZoneForSystem(this);

        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Time zone:", timezone_.getDisplayName());
        return timezone_;
    }

    /**
     * Returns the timezone of the IBM i, if available. If the timezone is not available, then the default timezone for
     * the client will be return.
     * 
     * @param system System to get the timezone from
     * @return The timezone of the IBM i if available.
     */
    public static TimeZone getDefaultTimeZone(AS400 system)
    {
        TimeZone timeZone = null;
        if (system != null)
        {
            try {
                timeZone = system.getTimeZone();
            } catch (Exception e) {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Exception obtaining timezone ", e);
            }
        }
        
        if (timeZone == null)
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Defaulting to local timezone");
            timeZone = TimeZone.getDefault();
        }

        return timeZone;
    }


    /**
     * Returns the user ID. The user ID returned may be set as a result of the constructor, or it may be what the user
     * typed in at the sign-on prompt.
     * 
     * @return The user ID, or an empty string ("") if not set.
     **/
    public String getUserId()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting user ID:", userId_);
        userId_ = resolveUserId(userId_, credVault_.getType(), mustUseSuppliedProfile_);
        return userId_;
    }

    /**
     * Returns the user ID. The user ID returned may be set as a result of the constructor, or it may be what the user
     * typed in at the sign-on prompt.
     * 
     * @param forceRefresh If true, force the current userID information to be reloaded. When running natively with
     *                     system name specified as <code>localhost</code>, this will obtain the user profile under
     *                     which the thread is currently running. This may have changed since object construction, if a
     *                     profile swap has been performed on the thread. If false, or if running remotely, then this
     *                     method behaves identically to {@link #getUserId getUserId()}.
     * @return The user ID, or an empty string ("") if not set.
     **/
    public String getUserId(boolean forceRefresh)
    {
        if (!forceRefresh)
            return getUserId();

        if (Trace.traceOn_)
            Trace.log(Trace.DIAGNOSTIC, "Getting current user ID:", userId_);
        
        String currentUserID = userId_;
        if (systemNameLocal_ && AS400.onAS400)
        {
            try {
                currentUserID = CurrentUser.getUserID(AS400.nativeVRM.getVersionReleaseModification());
                if (currentUserID == null || currentUserID.length() == 0)
                    currentUserID = userId_;
            }
            catch (Throwable t) {
                if (Trace.traceOn_)
                    Trace.log(Trace.DIAGNOSTIC, t);
                currentUserID = userId_;
            }
        }

        return currentUserID;
    }

    /**
     * Returns the version of the IBM i system.
     * <p>
     * A connection is required to the system to retrieve this information. If a connection has not been established,
     * one is created to retrieve the system information.
     * 
     * @return The version of the IBM i system. For example, version 5, release 1, modification level 0, returns 5.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public int getVersion() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting version level.");
        if (signonInfo_ == null) {
            chooseImpl();
            signon(false);
        }
        int version = signonInfo_.version.getVersion();
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Version level:", version);

        return version;
    }
    
    /**
     * Returns the version, release, and modification level a given system.
     * 
     * @param systemName The IP address or hostname of the target system.
     * @param useSSL     Whether or not secure connections should be used when communicating with the host servers.
     * 
     * @return The high 16-bit is the version, the next 8 bits is the release, and the low 8 bits is the modification
     *         level. Thus version 5, release 1, modification level 0, returns 0x00050100.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public static int getVRM(String systemName, boolean useSSL) throws AS400SecurityException, IOException {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting vrm for system: " + systemName + ", use SSL:", useSSL);
        return AS400ImplRemote.getVRM(systemName, useSSL);
    }

    /**
     * Returns the version, release, and modification level for the system.
     * <p>
     * A connection is required to the system to retrieve this information. If a connection has not been established,
     * one is created to retrieve the system information.
     * 
     * @return The high 16-bit is the version, the next 8 bits is the release, and the low 8 bits is the modification
     *         level. Thus version 5, release 1, modification level 0, returns 0x00050100.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public int getVRM() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting VRM.");
        if (signonInfo_ == null)
        {
            chooseImpl();
            signon(false);
        }
        
        int vrm = signonInfo_.version.getVersionReleaseModification();
        if (Trace.traceOn_)
        {
            byte[] vrmBytes = new byte[4];
            BinaryConverter.intToByteArray(vrm, vrmBytes, 0);
            Trace.log(Trace.DIAGNOSTIC, "VRM:",  vrmBytes);
        }

        return vrm;
    }

    /**
     * Sets the VRM for the object, creating a signonInfo as needed
     * 
     */
    protected void setVRM(int v, int r, int m)
    {
        if (signonInfo_ == null)
            signonInfo_ = new SignonInfo((v << 16 ) + (r << 8) + m); 
        else
            signonInfo_.version.setVersionReleaseModification((v << 16) + (r << 8) + m);

        if (impl_ != null)
          impl_.setVRM(v,r,m);
    }
    
    /**
     * Initialize conversion table for the given CCSID. The default EBCDIC to unicode converters are not shipped with
     * some browsers. This method can be used to check and download converters if they are not available locally.
     * 
     * @param ccsid the CCSID for the conversion table to initialize.
     * @throws UnsupportedEncodingException If the Character Encoding is not supported.
     **/
    public void initializeConverter(int ccsid) throws UnsupportedEncodingException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Initializing converter for CCSID:", ccsid);
        chooseImpl();
        try
        {
            signon(false);
            impl_.newConverter(ccsid);
        }
        catch (Exception e)
        {
            Trace.log(Trace.WARNING, "Error initializing converter:", e);
            throw new UnsupportedEncodingException();
        }
    }

    /**
     * Indicates if any service is currently connected through this object.
     * <p>
     * A service is considered "connected" if connectService() has been called, or an implicit connect has been done by
     * the service, and disconnectService() or disconnectAllServices() has not been called. If the most recent attempt
     * to contact the service failed with an exception, the service is considered disconnected.
     * 
     * @return true if any service is connected; false otherwise.
     * @see #isConnectionAlive
     **/
    public boolean isConnected()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking for any service connection...");
        boolean flag = false;
        if (    isConnected(AS400.HOSTCNN)      || isConnected(AS400.FILE)      || isConnected(AS400.PRINT) 
             || isConnected(AS400.COMMAND)      || isConnected(AS400.DATAQUEUE) || isConnected(AS400.DATABASE) 
             || isConnected(AS400.RECORDACCESS) || isConnected(AS400.CENTRAL)   || isConnected(AS400.SIGNON) )
            flag = true;
        
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Is a service connected? " + flag);
        return flag;
    }

    /**
     * Indicates if a service is currently connected through this object.
     * <p>
     * A service is considered "connected" if connectService() has been called, or an implicit connect has been done by
     * the service, and disconnectService() or disconnectAllServices() has not been called. If the most recent attempt
     * to contact the service failed with an exception, the service is considered disconnected.
     * 
     * @param service The name of the service. Valid services are:
     *                <ul>
     *                <li>{@link #FILE FILE} - IFS file classes.
     *                <li>{@link #PRINT PRINT} - print classes.
     *                <li>{@link #COMMAND COMMAND} - command and program call classes.
     *                <li>{@link #DATAQUEUE DATAQUEUE} - data queue classes.
     *                <li>{@link #DATABASE DATABASE} - JDBC classes.
     *                <li>{@link #RECORDACCESS RECORDACCESS} - record level access classes.
     *                <li>{@link #CENTRAL CENTRAL} - license management classes.
     *                <li>{@link #SIGNON SIGNON} - sign-on classes.
     *                <li>{@link #HOSTCNN HOSTCNN} - host connection classes.
     *                </ul>
     * @return true if service is connected; false otherwise.
     * @exception ExtendedIllegalArgumentException If service is not valid.
     * @see #isConnectionAlive
     **/
    public boolean isConnected(int service)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking for service connection:", service);

        if (service < 0 || service > 8)
            throw new ExtendedIllegalArgumentException("service (" + service + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);

        boolean connected = (impl_ == null) ? false : impl_.isConnected(service);
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Service connection:", connected);
        return connected;
    }

    /**
     * Tests the connection to the system, to verify that it is still working. This is similar in concept to "pinging"
     * the system over the connection. If no services have been connected, this method returns false; it doesn't
     * implicitly connect services.
     * <p>
     * Note: This method is <b>not fully supported until IBM i 7.1</b>. If running to IBM i 6.1 or lower, then the
     * behavior of this method matches that of {@link #isConnected() isConnected()}, and therefore may incorrectly
     * return <tt>true</tt> if the connection has failed recently.
     * <p>
     * Note: If the only service connected is {@link #RECORDACCESS RECORDACCESS}, then this method defaults to the
     * behavior of {@link #isConnected() isConnected()}.
     * 
     * @return true if the connection is still working; false otherwise.
     * @see #isConnected
     * @see AS400JPing
     **/
    public boolean isConnectionAlive()
    {
      if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Testing connection...");

      boolean alive = (impl_ == null) ? false : impl_.isConnectionAlive();
      if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Connection status:", alive);
      return alive;
    }

    /**
     * Tests the connection to a service on the system, to verify that it is still working. This is similar in concept
     * to "pinging" the system over the connection. If no services have been connected, this method returns false; it
     * doesn't implicitly connect services.
     * <p>
     * Note: This method is <b>not fully supported until IBM i 7.1</b>. If running to IBM i 6.1 or lower, then the
     * behavior of this method matches that of {@link #isConnected() isConnected()}, and therefore may incorrectly
     * return <tt>true</tt> if the connection has failed recently.
     * <p>
     * Note: If the specified service is {@link #RECORDACCESS RECORDACCESS}, then this method defaults to the behavior
     * of {@link #isConnected() isConnected()}.
     * 
     * @param service The name of the service. Valid services are:
     *                <ul>
     *                <li>{@link #FILE FILE} - IFS file classes.
     *                <li>{@link #PRINT PRINT} - print classes.
     *                <li>{@link #COMMAND COMMAND} - command and program call classes.
     *                <li>{@link #DATAQUEUE DATAQUEUE} - data queue classes.
     *                <li>{@link #DATABASE DATABASE} - JDBC classes.
     *                <li>{@link #RECORDACCESS RECORDACCESS} - record level access classes.
     *                <li>{@link #CENTRAL CENTRAL} - license management classes.
     *                <li>{@link #SIGNON SIGNON} - sign-on classes.
     *                <li>{@link #HOSTCNN HOSTCNN} - host connection classes.
     *                </ul>
     * @return true if the connection to the service is still working; false otherwise.
     * @see #isConnected
     * @see AS400JPing
     **/
    public boolean isConnectionAlive(int service)
    {
      if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Testing connection...");

      boolean alive = (impl_ == null) ? false : impl_.isConnectionAlive(service);

      if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Connection status:", alive);
      return alive;
    }

    /**
     * Returns the sign-on prompting mode for this object. If true, then messages are displayed. If warnings or errors
     * occur, the sign-on and change password dialogs are displayed if needed. If false, warnings and errors result in
     * exceptions, and password dialogs are not displayed. The caller has to provide the user ID and password.
     * 
     * @return true if using GUI; false otherwise.
     **/
    public boolean isGuiAvailable()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if GUI is available:", guiAvailable_);
        return guiAvailable_;
    }

    /**
     * Indicates if this object is representing the system you are currently running on.
     * 
     * @return true if you are running on the local system; false otherwise.
     **/
    public boolean isLocal()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if local:", systemNameLocal_);
        return systemNameLocal_;
    }

    /**
     * When your Java program runs on the system, some Toolbox classes access data via a call to an API instead of
     * making a socket call to the system. There are minor differences in the behavior of the classes when they use API
     * calls instead of socket calls. If your program is affected by these differences you can check whether the Toolbox
     * classes will use socket calls instead of API calls by using this method.
     * 
     * @return true if you have indicated that the services must use sockets; false otherwise.
     **/
    public boolean isMustUseSockets()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if must use sockets:", mustUseSockets_);
        return mustUseSockets_;
    }

    /**
     * Indicates if checkboxes should be shown on the sign-on dialog.
     * 
     * @return true if checkboxes should be shown; false otherwise.
     **/
    public boolean isShowCheckboxes()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if checkboxes are shown:", showCheckboxes_);
        return showCheckboxes_;
    }

    // Check if systemName refers to the system we are running on.
    private static boolean isSystemNameLocal(String systemName)
    {
        if (systemName.equalsIgnoreCase("localhost"))
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "System name is 'localhost'.");
            return true;
        }

        try
        {
            InetAddress localInet = InetAddress.getLocalHost();
            InetAddress[] remoteInet = InetAddress.getAllByName(systemName);

            for (int i = 0; i < remoteInet.length; ++i)
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Comparing local address " + localInet + " to " + remoteInet[i]);
                if (localInet.equals(remoteInet[i]))
                    return true;
            }
        }
        catch (UnknownHostException e) {
            Trace.log(Trace.ERROR, "Error retrieving host address information:", e);
        }

        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "System name is not local.");
        return false;
    }

    /**
     * Indicates whether threads are used in communication with the host servers.
     * 
     * @return true if threads are used; false otherwise.
     **/
    public boolean isThreadUsed()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if thread is used:", threadUsed_);
        return threadUsed_;
    }

    /**
     * Indicates if the default user should be used by this object. If the default user is not used and a user ID was
     * not specified on the constructor, then the user will be prompted for a user ID.
     * 
     * @return true if default user should be used; false otherwise.
     **/
    public boolean isUseDefaultUser()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if default user is used:", useDefaultUser_);
        return useDefaultUser_;
    }

    /**
     * Indicates if the password cache is being used by this object. If the password cache is not used, the user will
     * always be prompted for password if one was not provided.
     * 
     * @return true if password cache is being used; false otherwise.
     **/
    public boolean isUsePasswordCache()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if password cache is used:", usePasswordCache_);
        return usePasswordCache_;
    }
    
    /**
     * Is the AS400 object configured to use a pass phrase
     * 
     * @return true if pass phrase can be used
     * @throws AS400SecurityException If a security or authority error occurs.
     * @throws IOException            If an error occurs while communicating with the system.
     */
    public boolean isUsePassphrase() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if use password phrase");
        
        chooseImpl();
        signon(false);
        
        boolean ret = false;
        if (impl_ instanceof AS400ImplRemote)
          ret = ((AS400ImplRemote)impl_).getPasswordType();
        
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Use password phrase: " + ret);

        return ret;
    }

    // Load the specified implementation object.  Exceptions are swallowed, null is returned if the object cannot be loaded.
    static Object loadImpl(String impl)
    {
        if (impl.indexOf ('.') == -1)
            impl = "com.ibm.as400.access." + impl;

        if (Trace.traceOn_ && alreadyCheckedForMultipleVersions_++ < 10)
        {
            Trace.log(Trace.DIAGNOSTIC, "Checking for multiple Toolbox versions.");
            try
            {
                String thisFileName = "com/ibm/as400/access/AS400.class";
                String loadFileName = impl.replace('.', '/') + ".class";
                ClassLoader thisLoader = Class.forName(CLASSNAME).getClassLoader();
                ClassLoader loadLoader = Class.forName(impl).getClassLoader();
                if (thisLoader != null && loadLoader != null)
                {
                  URL thisUrl = thisLoader.getResource(thisFileName);
                  URL loadUrl = loadLoader.getResource(loadFileName);
                  if (thisUrl != null && loadUrl != null)
                  {
                    String thisPath = thisUrl.getPath();
                    String loadPath = loadUrl.getPath();
                    Trace.log(Trace.DIAGNOSTIC, "Path of AS400 class:", thisPath);
                    Trace.log(Trace.DIAGNOSTIC, "Path of loaded impl class:", loadPath);
                    String thisDirPath = (thisPath.length() <= thisFileName.length()) ? "" : thisPath.substring(0, thisPath.length() - thisFileName.length() - 1);
                    String loadDirPath = (loadPath.length() <= loadFileName.length()) ? "" : loadPath.substring(0, loadPath.length() - loadFileName.length() - 1);
                    if (!thisDirPath.equals(loadDirPath))
                    {
                      Trace.log(Trace.WARNING, "Toolbox classes found in two different locations: " + thisDirPath + " and " + loadDirPath);
                    }
                  }
                }
            }
            catch (ClassNotFoundException e) {
                Trace.log(Trace.DIAGNOSTIC, "Class not found:", e.getMessage());
            }
            catch (Throwable e) {
                Trace.log(Trace.DIAGNOSTIC, e);
            }
        }

        try {
            return Class.forName(impl).newInstance();
        } catch (ClassNotFoundException e1) {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Class not found:", e1.getMessage());
        } catch (IllegalAccessException e2) {
            Trace.log(Trace.ERROR, "Unexpected IllegalAccessException:", e2);
        } catch (InstantiationException e3) {
            Trace.log(Trace.ERROR, "Unexpected InstantiationException:", e3);
        }

        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Load of implementation failed:", impl);

        return null;
    }

    // Load the appropriate implementation object.
    // param  impl1  fully package named class name for native implementation.
    // param  impl2  fully package named class name for remote implementation.
    Object loadImpl(String impl1, String impl2)
    {
        if (canUseNativeOptimizations())
        {
            Object impl = loadImpl(impl1);
            if (impl != null) return impl;
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Load of native implementation '" + impl1 + "' failed, attempting to load remote implementation.");
        }
        
        Object impl = loadImpl(impl2);
        if (impl != null) return impl;

        Trace.log(Trace.DIAGNOSTIC, "Load of remote implementation '" + impl2 + "' failed.");
        throw new ExtendedIllegalStateException(impl2, ExtendedIllegalStateException.IMPLEMENTATION_NOT_FOUND);
    }

    // Load the appropriate implementation object when only remote or proxy implementations are involved.
    // param  impl1  fully package named class name for remote implementation.
    // param  impl2  fully package named class name for proxy implementation.
    Object loadImpl2(String impl1, String impl2)
    {
        if (proxyServer_.length() > 0)
        {
            synchronized (this)
            {
                if (proxyClientConnection_ == null)
                    proxyClientConnection_ = new ProxyClientConnection(proxyServer_, useSSLConnection_);
            }
            ProxyImpl proxyImpl = (ProxyImpl)loadImpl(impl2);
            if (proxyImpl != null)
            {
                proxyImpl.construct((ProxyClientConnection)proxyClientConnection_);
                return proxyImpl;
            }
        }

        Object impl = loadImpl(impl1);
        if (impl != null) return impl;

        Trace.log(Trace.DIAGNOSTIC, "Load of remote implementation '" + impl1 + "' failed.");
        throw new ExtendedIllegalStateException(impl1, ExtendedIllegalStateException.IMPLEMENTATION_NOT_FOUND);
    }

    // Load the appropriate implementation object when a remote, proxy, or native implementations are involved.
    // param  impl1  fully package named class name for native implementation.
    // param  impl2  fully package named class name for remote implementation.
    // param  impl3  fully package named class name for proxy implementation.
    Object loadImpl3(String impl1, String impl2, String impl3)
    {
        if (proxyServer_.length() > 0)
        {
            synchronized (this)
            {
                if (proxyClientConnection_ == null)
                    proxyClientConnection_ = new ProxyClientConnection(proxyServer_, useSSLConnection_);
            }
            ProxyImpl proxyImpl = (ProxyImpl)loadImpl(impl3);
            if (proxyImpl != null)
            {
                proxyImpl.construct((ProxyClientConnection)proxyClientConnection_);
                return proxyImpl;
            }
        }

        if (canUseNativeOptimizations())
        {
            Object impl = loadImpl(impl1);
            if (impl != null) return impl;
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Load of native implementation '" + impl1 + "' failed, attempting to load remote implementation.");
        }

        Object impl = loadImpl(impl2);
        if (impl != null) return impl;

        Trace.log(Trace.DIAGNOSTIC, "Load of remote implementation '" + impl2 + "' failed.");
        throw new ExtendedIllegalStateException(impl2, ExtendedIllegalStateException.IMPLEMENTATION_NOT_FOUND);
    }

    // State machine constants for running through sign-on prompts.
    private static final int FINISHED = 0;
    private static final int VALIDATE = 1;
    private static final int PROMPT = 2;
    // Maximum number of iterations of the state machine before we suspect infinite loop.
    private static final int MAX_ITERATIONS = 20;  // Future enhancement: Make this configurable
    // Run through the various prompts for signon.
    private void promptSignon() throws AS400SecurityException, IOException
    {
        if (signingOn_)
        {
            Trace.log(Trace.ERROR, "AS400.promptSignon() called while already signing on.  SignonHandler may have called a prohibited method.");
            throw new ExtendedIllegalStateException(ExtendedIllegalStateException.SIGNON_ALREADY_IN_PROGRESS);
        }

        try
        {
            signingOn_ = true;  // Detect/prevent recursion.
            boolean reconnecting = (signonInfo_ != null);  // Is this a reconnection.

            // Start in validate state.
            int pwState = VALIDATE;
            SignonHandler soHandler = getSignonHandler();

            // If something isn't set, go to prompt state.
            if (credVault_.getType() == AUTHENTICATION_SCHEME_PASSWORD 
                    && (systemName_.length() == 0 || userId_.length() == 0 || credVault_.isEmpty() 
                          || !(soHandler instanceof ToolboxSignonHandler) || forcePrompt_))
            {
                pwState = PROMPT;
            }

            int counter = 0;  // Loop counter to detect infinite looping.
            boolean proceed = true;

            do
            {
                counter++;
                try
                {
                    switch (pwState)
                    {
                        case VALIDATE:
                            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Validate security...");
                            sendSignonRequest();
                            break;
                        case PROMPT:
                            if(!isGuiAvailable() && forcePrompt_)
                            { 
                                //JDBC flagged id/pass as invalid and set forcePrompt, but GUI not available 
                                //So don't even try to authenticate because it could be a non-safe password. 
                                Trace.log(Trace.ERROR, "No GUI available for signon dialog.");            
                                handlerCanceled_ = true;  // Don't submit exception to handler.                 
                                throw new AS400SecurityException(AS400SecurityException.SIGNON_CHAR_NOT_VALID); 
                            }
                            
                            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Calling SignonHandler...");
                            // If bytes_ has not been set, tell the handler something is missing.
                            SignonEvent soEvent = new SignonEvent(this, reconnecting);
                            proceed = soHandler.connectionInitiated(soEvent, credVault_.isEmpty());
                            if (!proceed)
                            {
                                // User canceled.
                                Trace.log(Trace.DIAGNOSTIC, "User canceled.");
                                handlerCanceled_ = true;  // Don't submit exception to handler.
                                throw new AS400SecurityException(AS400SecurityException.SIGNON_CANCELED);
                            }

                            sendSignonRequest();

                            // See if we should cache the password.
                            if (isUsePasswordCache() && credVault_.getType() == AUTHENTICATION_SCHEME_PASSWORD)
                            {
                                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting password cache entry from SignonHandler...");
                                setCacheEntry(systemName_, userId_, credVault_);
                            }
                            break;
                        default:  // This should never happen.
                            Trace.log(Trace.ERROR, "Invalid password prompt state:", pwState);
                            throw new InternalErrorException(InternalErrorException.SECURITY_INVALID_STATE, pwState);
                    }

                    // Check for number of days to expiration, and warn if within threshold.
                    if ((!skipSignonServer_) &&  isInPasswordExpirationWarningDays()) /*@V1C*/
                    {
                        SignonEvent soEvent = new SignonEvent(this, reconnecting);
                        proceed = soHandler.passwordAboutToExpire(soEvent, getDaysToExpiration());
                        if (!proceed)
                        {
                            handlerCanceled_ = true;  // Don't submit exception to handler.
                            throw new AS400SecurityException(AS400SecurityException.SIGNON_CANCELED);
                        }
                    }
                    pwState = FINISHED;  // If we got this far, we're done.
                }
                catch (AS400SecurityException e)
                {
                    if (handlerCanceled_) throw e;  // Handler already gave up on this event.
                    Trace.log(Trace.ERROR, "Security exception in sign-on:", e);
                    SignonEvent soEvent = new SignonEvent(this, reconnecting, e);
                    switch (e.getReturnCode())
                    {
                        case AS400SecurityException.PASSWORD_EXPIRED:
                            proceed = soHandler.passwordExpired(soEvent);
                            break;
                        case AS400SecurityException.PASSWORD_NOT_SET:
                            proceed = soHandler.passwordMissing(soEvent);
                            break;
                        case AS400SecurityException.PASSWORD_INCORRECT:
                        case AS400SecurityException.PASSWORD_OLD_NOT_VALID:
                            proceed = soHandler.passwordIncorrect(soEvent);
                            break;
                        case AS400SecurityException.PASSWORD_LENGTH_NOT_VALID:
                        case AS400SecurityException.PASSWORD_NEW_TOO_LONG:
                        case AS400SecurityException.PASSWORD_NEW_TOO_SHORT:
                            proceed = soHandler.passwordLengthIncorrect(soEvent);
                            break;
                        case AS400SecurityException.PASSWORD_INCORRECT_USERID_DISABLE:
                            proceed = soHandler.userIdAboutToBeDisabled(soEvent);
                            break;
                        case AS400SecurityException.USERID_UNKNOWN:
                            proceed = soHandler.userIdUnknown(soEvent);
                            break;
                        case AS400SecurityException.USERID_DISABLE:
                            proceed = soHandler.userIdDisabled(soEvent);
                            break;
                        default:
                            soHandler.exceptionOccurred(soEvent);  // Handler rethrows if can't handle.
                    }
                    if (!proceed) { throw e; }

                    // Assume handler has corrected any incorrect values. Prepare to try again.
                    pwState = VALIDATE;
                }
                catch (UnknownHostException e)
                {
                    SignonEvent soEvent = new SignonEvent(this, reconnecting);
                    proceed = soHandler.systemNameUnknown(soEvent, e);
                    if (!proceed) { throw e; }
                    pwState = VALIDATE;
                }
            }
            while (pwState != FINISHED && counter < MAX_ITERATIONS);

            if (pwState != FINISHED)
            {
                Trace.log(Trace.ERROR, "Possible infinite loop while interacting with SignonHandler.");
                throw new AS400SecurityException(AS400SecurityException.SIGNON_REQUEST_NOT_VALID);
            }
        }
        finally
        {
            signingOn_ = false;
            forcePrompt_ = false;  //@prompt
        }
    }

    // Help de-serialize the object.
    private void readObject(ObjectInputStream in) throws ClassNotFoundException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "De-serializing AS400 object.");
        in.defaultReadObject();

        construct();

        systemNameLocal_ = resolveSystemNameLocal(systemName_);

        proxyServer_ = resolveProxyServer("");
        // proxyClientConnection_ can stay null.
        ccsid_ = 0;
        // connectionListeners_ can stay null.
        // dispatcher_ can stay null.
        // propertyChangeListeners_ can stay null.
        // vetoableChangeListeners_ can stay null.

        propertiesFrozen_ = false;
        // impl_ can stay null.
        // signonInfo_ can stay null.

        // Default to password authentication, just like we would if
        // an AS400 object was constructed with no parameters.
        credVault_ = new PasswordVault();
    }

    /**
     * Removes a listener from the connection event list.
     * 
     * @param listener The listener object.
     **/
    public void removeConnectionListener(ConnectionListener listener)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Removing connection listener.");
        if (listener == null)
            throw new NullPointerException("listener");

        synchronized (this)
        {
            // If we have listeners.
            if (connectionListeners_ != null)
            {
                connectionListeners_.removeElement(listener);
                // If we have a connection, and we're now out of listeners.
                if (impl_ != null && connectionListeners_.isEmpty())
                {
                    // Remove the dispatcher.
                    impl_.removeConnectionListener(dispatcher_);
                }
            }
        }
    }

    /**
     * Removes the default user for the given system name.
     * 
     * @param systemName The name of the IBM i system.
     **/
    public static void removeDefaultUser(String systemName)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Removing the default user, system name:", systemName);
        if (systemName == null)
            throw new NullPointerException("systemName");

        AS400.defaultUsers.remove(resolveSystem(systemName));
    }

    /**
     * Removes the password cache entry associated with this system name and user ID. Only applies within this Java
     * virtual machine.
     * 
     * @param systemName The name of the IBM i system.
     * @param userId     The user profile name.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     **/
    public static void removePasswordCacheEntry(String systemName, String userId)
    {
        boolean isLocalHost = false;
        String longName = null;
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Removing password cache entry, system name: " + systemName + " user ID: " + userId);
        if (systemName == null)
            throw new NullPointerException("systemName");
 
        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        systemName = resolveSystem(systemName);

        boolean localHost = systemName.equals("localhost");
        if (localHost)
        {
            isLocalHost = true;
            try {
                systemName = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) { /* ignore */ }
        } else {
            // Note. The check to see if the current system was also local host was
            // done in resolveSystem.
        }
        
        int dotIndex = systemName.indexOf(".");
        if (dotIndex > 0) {
            longName = systemName;
            systemName = systemName.substring(0,dotIndex);
        }

        if (isTurkish()) {
            userId = userId.toUpperCase(Locale.ENGLISH);
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }

        userId = resolveUserId(userId.toUpperCase());

        synchronized (AS400.systemList)
        {
            for (int i = AS400.systemList.size() - 1; i >= 0; i--)
            {
                Object[] secobj = (Object[])AS400.systemList.elementAt(i);
                if (systemName.equalsIgnoreCase((String)secobj[0]) && userId.equals(secobj[1]))
                    AS400.systemList.removeElementAt(i);
                else if (isLocalHost && "localhost".equalsIgnoreCase((String)secobj[0]) && userId.equals(secobj[1]))
                    AS400.systemList.removeElementAt(i);
                else if (longName != null  && longName.equalsIgnoreCase((String)secobj[0]) && userId.equals(secobj[1]))
                    AS400.systemList.removeElementAt(i);
            }
        }
    }

    /**
     * Removes a property changed listener from the listener list.
     * 
     * @param listener The listener object.
     **/
    public void removePropertyChangeListener(PropertyChangeListener listener)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Removing property change listener.");
        if (listener == null)
            throw new NullPointerException("listener");

        // If we have listeners.
        if (propertyChangeListeners_ != null)
            propertyChangeListeners_.removePropertyChangeListener(listener);
    }

    /**
     * Removes a listener from the veto list.
     * 
     * @param listener The listener object.
     **/
    public void removeVetoableChangeListener(VetoableChangeListener listener)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Removing vetoable change listener.");
        if (listener == null)
            throw new NullPointerException("listener");

        // If we have listeners.
        if (vetoableChangeListeners_ != null)
            vetoableChangeListeners_.removeVetoableChangeListener(listener);
    }

    /**
     * Disconnects all services, and clears the sign-on information. This intent of this method is to "wipe the slate
     * clean" for this AS400 object, enabling connection properties to be subsequently changed.
     * <P>
     * Note: A call to resetAllServices() results in the stay-alive seconds value to be reset to zero.  You will 
     * need to invoke the setStayAlive() method to re-enable the stay-alive functionality. 
     * 
     * @see #disconnectAllServices
     * @see #setStayAlive(long)
     **/
    public synchronized void resetAllServices()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Resetting all services.");
        setStayAlive(0);

        disconnectAllServices();
        disconnectService(AS400.HOSTCNN);
        signonInfo_ = null;
        propertiesFrozen_ = false;
        ccsid_ = 0;
    }

    // Resolves the proxy server name.  If it is not specified, then look it up in the system properties.  Returns empty string if not set.
    private static String resolveProxyServer(String proxyServer)
    {
        if (proxyServer.length() == 0)
        {
            proxyServer = SystemProperties.getProperty(SystemProperties.AS400_PROXY_SERVER);
            if (proxyServer == null) return "";
        }
        return proxyServer;
    }

    // If connecting to local system, make systemName "localhost".
    static String resolveSystem(String systemName)
    {
        // First, see if we are running on IBM i.
        if (AS400.onAS400)
        {
            // If system name is null, then make it a localhost.
            if (systemName.length() == 0)
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Resolving initial system name to 'localhost'.");
                return "localhost";
            }
            else if (isSystemNameLocal(systemName))
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Resolving system name to 'localhost'.");
                return "localhost";
            }
        }
        return systemName;
    }

    // Convenience method to determine if systemName is local system.
    static boolean resolveSystemNameLocal(String systemName)
    {
        if (AS400.onAS400)
            if (systemName.length() == 0 || isSystemNameLocal(systemName)) return true;
        return false;
    }

    // If on the system, resolve user ID to current user ID.
    static String resolveUserId(String userId)
    {
        // Resolve user ID, for someone using user ID/password.
        return resolveUserId(userId, AUTHENTICATION_SCHEME_PASSWORD, false);
    }

    private static boolean currentUserAvailable = true;
    private static boolean currentUserTried = false;
    static boolean currentUserAvailable()
    {
        if (!currentUserTried)
        {
            try
            {
                Class.forName("com.ibm.as400.access.CurrentUser");
            }
            catch (Throwable t)
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "CurrentUser class is not available:", t);
                currentUserAvailable = false;
            }
            currentUserTried = true;
        }
        return currentUserAvailable;
    }

    // If on the system, resolve user ID to current user ID.
    static String resolveUserId(String userId, int authScheme, boolean mustUseSuppliedProfile)
    {
        // First, see if we are running on the system.
        if (AS400.onAS400 && !mustUseSuppliedProfile && currentUserAvailable())
        {
            boolean tryToGetCurrentUserID = false;
            // If user ID is not set and we're using user ID/password, then we get it and set it up.
            if (userId.length() == 0 && authScheme == AUTHENTICATION_SCHEME_PASSWORD)
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Resolving initial user ID.");
                tryToGetCurrentUserID = true;
            }
            
            // If we are running on the system, then *CURRENT for user ID means we want to connect using current user ID.
            if (userId.equals("*CURRENT"))
            {
                // Get current user ID and use it.
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Replacing *CURRENT as user ID.");
                tryToGetCurrentUserID = true;
            }
            
            if (tryToGetCurrentUserID)
            {
                String currentUserID = CurrentUser.getUserID(AS400.nativeVRM.getVersionReleaseModification());
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Current user ID:", currentUserID);
                if (currentUserID != null) return currentUserID;
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Current user ID information not available, user ID: '"  + userId + "'");
            }
        }

        // Prepend Q to numeric user ID.
        if (userId.length() > 0 && Character.isDigit(userId.charAt(0)))
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Prepending 'Q' to numeric user ID.");
            userId = "Q" + userId;
        }

        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "User ID: '"  + userId + "'");
        return userId;
    }

    // Send sign-on request to sign-on server.
    private void sendSignonRequest() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Signing-on without prompting...");
        // No prompting.
        if (credVault_.isEmpty() && !userIdMatchesLocal(userId_, mustUseSuppliedProfile_))
            throw new AS400SecurityException(AS400SecurityException.PASSWORD_NOT_SET);

        // If using GSS tokens, don't bother encoding the authentication info.
        if (credVault_.getType() == AUTHENTICATION_SCHEME_GSS_TOKEN)
        {
            signonInfo_ = impl_.signon(systemName_, systemNameLocal_, userId_, credVault_, gssName_, null);

            if (gssCredential_ != null)
                impl_.setGSSCredential(gssCredential_);
            credVault_.empty();  // GSSToken is single use only.
        }
        else  // Encode the authentication info before sending vault to impl.
        {
            byte[] proxySeed = new byte[9];
            CredentialVault.rng.nextBytes(proxySeed);
            CredentialVault tempVault = (CredentialVault)credVault_.clone();
            tempVault.storeEncodedUsingExternalSeeds(proxySeed, impl_.exchangeSeed(proxySeed));

            if (PASSWORD_TRACE)  Trace.log(Trace.DIAGNOSTIC, "AS400 object proxySeed:", proxySeed);
          
            if (skipSignonServer_)
            {
                impl_.setAdditionalAuthenticationFactor(additionalAuthenticationFactor_);
                signonInfo_ = impl_.skipSignon(systemName_, systemNameLocal_, userId_, tempVault, gssName_);
            }
            else
                signonInfo_ = impl_.signon(systemName_, systemNameLocal_, userId_, tempVault, gssName_, additionalAuthenticationFactor_);
        }
        
        if (userId_.length() == 0) userId_ = signonInfo_.userId;
        
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Sign-on completed.");
    }

    /**
     * Sets or resets the identity token for this object. Using this method will clear any previously set authentication
     * information.
     * <p>
     * Note: Authentication via IdentityToken is supported in operating system release V5R3M0 and by PTF in operating
     * system releases V5R2M0 and V5R1M0.
     * 
     * @param identityToken The identity token.
     **/
    public void setIdentityToken(byte[] identityToken)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting identity token.");

        if (identityToken == null)
            throw new NullPointerException("identityToken");

        synchronized (this)
        {
            credVault_.empty();
            credVault_ = new IdentityTokenVault(identityToken);
            signonInfo_ = null;
        }
    }

    /**
     * Set the additional authentication factor for the AS400 object. This will be used when establishing host server
     * connections if the IBM i server supports multifactor authentication.
     * 
     * @param additionalAuthFactor The additional authentication factor.
     * @exception ExtendedIllegalArgumentException If additionalAuthFactor length is not valid.
     */
    public void setAdditionalAuthenticationFactor(char[] additionalAuthFactor)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting additional authentication factor. Length: " 
                     + ((additionalAuthFactor == null) ? 0 : additionalAuthFactor.length));

        if (additionalAuthFactor == null || additionalAuthFactor.length == 0)
            additionalAuthenticationFactor_ = null;
        else
        {
            if (additionalAuthFactor.length > ProfileTokenCredential.MAX_ADDITIONALAUTHENTICATIONFACTOR_LENGTH)
                throw new ExtendedIllegalArgumentException("additionalAuthFactor", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
            
            additionalAuthenticationFactor_ = Arrays.copyOf(additionalAuthFactor, additionalAuthFactor.length);
        }
        
        if (impl_ != null)
            impl_.setAdditionalAuthenticationFactor(additionalAuthenticationFactor_);
    }
    
    // Store information in password cache.
    private static void setCacheEntry(String systemName, String userId, CredentialVault pwVault)
    {
        synchronized (AS400.systemList)
        {
            // Remove any duplicates in the list and add to the list.
            for (int i = AS400.systemList.size() - 1; i >= 0; i--)
            {
                Object[] curPtr = (Object[])AS400.systemList.elementAt(i);

                if (systemName.equalsIgnoreCase((String)curPtr[0]) && userId.equals(curPtr[1]))
                {
                    // Found duplicate, remove it.
                    if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Adding password cache entry, removing previous entry.");
                    AS400.systemList.removeElementAt(i);
                }
            }

            // Now add the new one.
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Adding password cache entry for "+systemName+":"+userId+".");
            
            AS400.systemList.addElement(new Object[] {systemName, userId, pwVault.clone()} );
        }
    }

    /**
     * Sets the CCSID to be used for this object. The CCSID property cannot be changed once a connection to the system
     * has been established.
     * 
     * @param ccsid The CCSID to use for this object.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     * @exception PropertyVetoException If any of the registered listeners vetos the property change.
     **/
    public void setCcsid(int ccsid) throws PropertyVetoException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting CCSID:", ccsid);
        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set CCSID after connection has been made.");
            throw new ExtendedIllegalStateException("ccsid", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }

        if (propertyChangeListeners_ == null && vetoableChangeListeners_ == null)
            ccsid_ = ccsid;
        else
        {
            Integer oldValue = Integer.valueOf(ccsid_);
            Integer newValue = Integer.valueOf(ccsid);

            if (vetoableChangeListeners_ != null)
                vetoableChangeListeners_.fireVetoableChange("ccsid", oldValue, newValue);
            
            ccsid_ = ccsid;
            
            if (propertyChangeListeners_ != null)
                propertyChangeListeners_.firePropertyChange("ccsid", oldValue, newValue);
        }
    }

    /**
     * Sets the relational database name (RDB name) used for record-level access (DDM) connections. The RDB name
     * corresponds to the independent auxiliary storage pool (IASP) that it is using on the system. The RDB name cannot
     * be changed while this object is actively connected to the {@link #RECORDACCESS RECORDACCESS} service; you must
     * call {@link #disconnectService(int) AS400.disconnectService(AS400.RECORDACCESS)} first.
     * 
     * @param ddmRDB The name of the IASP or RDB to use, or null to indicate the default system ASP should be used.
     * @see #isConnected(int)
     * @see #getDDMRDB
     * @exception ExtendedIllegalArgumentException If ddmRDB length is not valid.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     **/
    public void setDDMRDB(String ddmRDB)
    {
        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set RDB name after connection has been made.");
            throw new ExtendedIllegalStateException("ddmRDB", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }
        
        if (ddmRDB != null && ddmRDB.length() > 18)
            throw new ExtendedIllegalArgumentException("ddmRDB", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
        
        if (isConnected(RECORDACCESS))
            throw new ExtendedIllegalStateException("ddmRDB", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        
        ddmRDB_ = ((ddmRDB == null) ? null : ddmRDB.toUpperCase());
    }

    /**
     * Sets the default sign-on handler, globally across the JVM. The specified handler object will be called at runtime
     * if any AS400 object needs to obtain additional signon information, and a sign-on handler has not been set on the
     * AS400 object via {@link #setSignonHandler setSignonHandler()}. <br>
     * Users are advised to implement the default sign-on handler in a thread-safe manner, since it may occasionally be
     * in simultaneous use by multiple threads. <br>
     * If a default sign-on handler is not set, then the name of the default sign-on handler class is retrieved from the
     * <em>com.ibm.as400.access.AS400.signonHandler</em> <a href="doc-files/SystemProperties.html">system property</a>.
     * <br>
     * If not specified, an internal AWT-based sign-on handler is used.
     * <p>
     * Note: This property may also be set by specifying a fully-qualified class name in Java system property
     * <tt>com.ibm.as400.access.AS400.signonHandler</tt>
     * 
     * @param handler The sign-on handler. Specifying <tt>null</tt> will reset the default sign-on handler to the
     *                internal AWT-based handler.
     * @see #getDefaultSignonHandler
     **/
    public static void setDefaultSignonHandler(SignonHandler handler)
    {
        if (Trace.traceOn_)
        {
            if (handler == null) Trace.log(Trace.DIAGNOSTIC, "Setting the default sign-on handler to null.");
            else if (defaultSignonHandler_ != null) Trace.log(Trace.DIAGNOSTIC, "Replacing default sign-on handler, formerly an instance of " + defaultSignonHandler_.getClass().getName());
        }
        defaultSignonHandler_ = handler;
    }

    /**
     * Sets the default user for a given system name. The default user is the user ID that is used to connect if a user
     * ID is not provided for that system name. There can be only one default user per system name. Once the default
     * user is set, it cannot be overridden. To change the default user, the caller should remove the default user and
     * then set it.
     * 
     * @param systemName The name of the IBM i system.
     * @param userId     The user profile name.
     * @return true if default user has been set; false otherwise.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     **/
    public static boolean setDefaultUser(String systemName, String userId)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting the default user, system name: '" + systemName + "' user ID: '" + userId + "'");
        if (systemName == null)
            throw new NullPointerException("systemName");

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        systemName = resolveSystem(systemName);

        if (isTurkish()) {
            userId = userId.toUpperCase(Locale.ENGLISH);
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }

        userId = resolveUserId(userId.toUpperCase());

        synchronized (AS400.defaultUsers)
        {
            if (AS400.defaultUsers.get(systemName) == null)
            {
                AS400.defaultUsers.put(systemName, userId);
                return true;
            }
        }
        // Already have a default user, fail the op.
        if (Trace.traceOn_) Trace.log(Trace.WARNING, "Default user already set, set default user failed.");
        return false;
    }
    
    /**
     * Sets the GSS manager to be used for all GSS operations.
     * 
     * @param gssMgr The GSS manager object.
     **/
    public static void setGSSManager(GSSManager gssMgr)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting GSS manager: '" + gssMgr + "'");
        gssManager_ = gssMgr;
    }
    
    static GSSManager getGSSManager() {
        return gssManager_;
    }

    /**
     * Sets the GSS credential for this object. Using this method will set the authentication scheme to
     * {@link #AUTHENTICATION_SCHEME_GSS_TOKEN AUTHENTICATION_SCHEME_GSS_TOKEN}. Only one authentication means (Kerberos
     * ticket, profile token, identity token, or password) can be used at a single time. Using this method will clear
     * any previously set authentication information.
     * 
     * @param gssCredential The GSS credential object.
     **/
    public void setGSSCredential(GSSCredential gssCredential)
    {
        if (gssCredential == null)
            throw new NullPointerException("gssCredential");

        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting GSS credential: '" + gssCredential + "'");

        synchronized (this)
        {
            gssCredential_ = gssCredential;
            gssName_ = "";
            credVault_.empty();
            credVault_ = new GSSTokenVault();
            signonInfo_ = null;

            if (impl_ != null)
                impl_.setGSSCredential(gssCredential_);
        }
    }

    /**
     * Sets the option for how the JGSS framework will be used to retrieve a GSS token for authenticating to the system.
     * By default, if no password or profile token is set on this object, it will attempt to retrieve a GSS token. If
     * that retrieval fails, a sign-on dialog can be presented, or on the system, the current user profile information
     * can be used. This option can also be set to only do the GSS token retrieval or to skip the GSS token retrieval.
     * 
     * @param gssOption A constant indicating how GSS will be used. Valid values are:
     *                  <ul>
     *                  <li>{@link #GSS_OPTION_MANDATORY GSS_OPTION_MANDATORY}
     *                  <li>{@link #GSS_OPTION_FALLBACK GSS_OPTION_FALLBACK}
     *                  <li>{@link #GSS_OPTION_NONE GSS_OPTION_NONE}
     *                  </ul>
     * @exception ExtendedIllegalArgumentException If gssOption is not valid.
     **/
    public void setGSSOption(int gssOption)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting GSS option:", gssOption);
        if (gssOption < 0 || gssOption > 2)
            throw new ExtendedIllegalArgumentException("gssOption (" + gssOption + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);

        gssOption_ = gssOption;
    }

    /**
     * Sets the GSS name for this object. Using this method will set the authentication scheme to
     * {@link #AUTHENTICATION_SCHEME_GSS_TOKEN AUTHENTICATION_SCHEME_GSS_TOKEN}. Only one authentication means (Kerberos
     * ticket, profile token, identity token, or password) can be used at a single time. Using this method will clear
     * any previously set authentication information.
     * 
     * @param gssName The GSS name string.
     **/
    public void setGSSName(String gssName)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting GSS name: '" + gssName + "'");

        if (gssName == null)
            throw new NullPointerException("gssName");

        synchronized (this)
        {
            gssName_ = gssName;
            gssCredential_ = null;
            credVault_.empty();
            credVault_ = new GSSTokenVault();
            signonInfo_ = null;
        }
    }

    /**
     * Sets the environment in which you are running. If guiAvailable is set to true, then prompting may occur during
     * sign-on to display error conditions, to prompt for additional information, or to prompt for change password. If
     * guiAvailable is set to false, then error conditions or missing information will result in exceptions.
     * Applications that are running as IBM i applications or want to control the sign-on user interface may want to run
     * with prompting mode set to false. Prompting mode is set to true by default.
     * <p>
     * Note: This property may also be set by specifying 'true' or 'false' in Java system property
     * <tt>com.ibm.as400.access.AS400.guiAvailable</tt>
     * 
     * @param guiAvailable true to prompt; false otherwise.
     * @exception PropertyVetoException If any of the registered listeners vetos the property change.
     * @see SignonHandler
     **/
    public void setGuiAvailable(boolean guiAvailable) throws PropertyVetoException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting GUI available:", guiAvailable);

        if (propertyChangeListeners_ == null && vetoableChangeListeners_ == null)
            guiAvailable_ = guiAvailable;
        else
        {
            Boolean oldValue = Boolean.valueOf(guiAvailable_);
            Boolean newValue = Boolean.valueOf(guiAvailable);

            if (vetoableChangeListeners_ != null)
                vetoableChangeListeners_.fireVetoableChange("guiAvailable", oldValue, newValue);

            guiAvailable_ = guiAvailable;
            if (propertyChangeListeners_ != null)
                propertyChangeListeners_.firePropertyChange("guiAvailable", oldValue, newValue);
        }
    }

    /**
     * Sets the Locale used to set the National Language Version (NLV) on the system. Only the COMMAND, PRINT, and
     * DATABASE services accept an NLV. This method will set the NLV based on a mapping from the Locale object to the
     * NLV.
     * 
     * @param locale The Locale object.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     **/
    public void setLocale(Locale locale)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting locale: " + locale);
        
        if (locale == null)
            throw new NullPointerException("locale");

        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set locale after connection has been made.");
            throw new ExtendedIllegalStateException("locale", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }

        if (propertyChangeListeners_ == null)
        {
            locale_ = locale;
            nlv_ = ExecutionEnvironment.getNlv(locale_);
        }
        else
        {
            Locale oldValue = locale_;
            Locale newValue = locale;

            locale_ = locale;
            nlv_ = ExecutionEnvironment.getNlv(locale_);
            propertyChangeListeners_.firePropertyChange("locale", oldValue, newValue);
        }
    }

    /**
     * Sets the Locale and a specific National Language Version (NLV) to send to the system. Only the COMMAND, PRINT,
     * and DATABASE services accept an NLV.
     * 
     * @param locale The Locale object.
     * @param nlv    The NLV.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     **/
    public void setLocale(Locale locale, String nlv)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting locale: " + locale + ", nlv: " + nlv_);
        
        if (locale == null)
            throw new NullPointerException("locale");

        if (nlv == null)
            throw new NullPointerException("nlv");

        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set locale after connection has been made.");
            throw new ExtendedIllegalStateException("locale", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }
        
        locale_ = locale;
        nlv_ = nlv;
    }

    /**
     * Sets this object to attempt to add the appropriate secondary language library to the library list, when running
     * on the system. The default is false. Setting the language library will ensure that any system error messages that
     * are returned, will be returned in the appropriate national language for the client locale. If the user profile
     * has insufficient authority to call CHGSYSLIBL, an error entry will appear in the job log, and the Toolbox will
     * disregard the error and proceed normally.
     * <p>
     * Note: This property may also be set by specifying 'true' or 'false' in Java system property
     * <tt>com.ibm.as400.access.AS400.mustAddLanguageLibrary</tt>
     * 
     * @param mustAddLanguageLibrary true to add language library; false otherwise.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     **/
    public void setMustAddLanguageLibrary(boolean mustAddLanguageLibrary)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting must add language library:", mustAddLanguageLibrary_);
        
        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set must add language library after connection has been made.");
            throw new ExtendedIllegalStateException("mustAddLanguageLibrary", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }
        mustAddLanguageLibrary_ = mustAddLanguageLibrary;
    }

    /**
     * Indicates whether this object will attempt to add the appropriate secondary language library to the library list,
     * when running on the system.
     * 
     * @return true if you have indicated that the secondary language library must be added; false otherwise.
     **/
    public boolean isMustAddLanguageLibrary()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if must add language library:", mustAddLanguageLibrary_);
        
        return mustAddLanguageLibrary_;
    }

    /**
     * Sets this object to using sockets. When your Java program runs on the system, some Toolbox classes access data
     * via a call to an API instead of making a socket call to the system. There are minor differences in the behavior
     * of the classes when they use API calls instead of socket calls. If your program is affected by these differences
     * you can force the Toolbox classes to use socket calls instead of API calls by using this method. The default is
     * false. The must use sockets property cannot be changed once a connection to the system has been established.
     * <p>
     * Note: This property may also be set by specifying 'true' or 'false' in Java system property
     * <tt>com.ibm.as400.access.AS400.mustUseSockets</tt>
     * 
     * @param mustUseSockets true to use sockets; false otherwise.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     **/
    public void setMustUseSockets(boolean mustUseSockets)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting 'must use sockets':", mustUseSockets);
        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set 'must use sockets' after connection has been made.");
            throw new ExtendedIllegalStateException("mustUseSockets", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }
        mustUseSockets_ = mustUseSockets;
    }

    /**
     * Indicates if Internet domain sockets only will be used.
     * 
     * @return true if must use Internet domain sockets only; false otherwise.
     **/
    public boolean isMustUseNetSockets()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if must use net sockets:", mustUseNetSockets_);
        return mustUseNetSockets_;
    }

    /**
     * Sets this object to using Internet domain sockets only. When your Java program runs on the system, some Toolbox
     * classes create UNIX domain socket connections. Using this method forces the Toolbox to only use Internet domain
     * sockets. The default is false. The must use net sockets property cannot be changed once a connection to the
     * system has been established.
     * <p>
     * Note: This property may also be set by specifying 'true' or 'false' in Java system property
     * <tt>com.ibm.as400.access.AS400.mustUseNetSockets</tt>
     * 
     * @param mustUseNetSockets true to use Internet domain sockets only; false otherwise.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     **/
    public void setMustUseNetSockets(boolean mustUseNetSockets)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting 'must use net sockets':", mustUseNetSockets);
        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set 'must use net sockets' after connection has been made.");
            throw new ExtendedIllegalStateException("mustUseNetSockets", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }
        mustUseNetSockets_ = mustUseNetSockets;
    }

    /**
     * Indicates if only a supplied profile will be used.
     * 
     * @return true if must use a supplied profile only; false otherwise.
     **/
    public boolean isMustUseSuppliedProfile()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if must use supplied profile:", mustUseSuppliedProfile_);
        return mustUseSuppliedProfile_;
    }

    /**
     * Sets this object to using a supplied profile only. When your Java program runs on the system, the information
     * from the currently signed-on user profile can be used. Using this method prevents the Toolbox from retrieving the
     * current user profile information. The default is false. The <tt>must use supplied profile</tt> property cannot be
     * changed once a connection to the system has been established.
     * <p>
     * Note: This property may also be set by specifying 'true' or 'false' in Java system property
     * <tt>com.ibm.as400.access.AS400.mustUseSuppliedProfile</tt>
     * 
     * @param mustUseSuppliedProfile true to use a supplied profile only; false otherwise.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     **/
    public void setMustUseSuppliedProfile(boolean mustUseSuppliedProfile)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting 'must use supplied profile':", mustUseSuppliedProfile);
        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set 'must use supplied profile' after connection has been made.");
            throw new ExtendedIllegalStateException("mustUseSuppliedProfile", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }
        mustUseSuppliedProfile_ = mustUseSuppliedProfile;
    }

    /**
     * Sets the password for this object. Only one authentication means (Kerberos ticket, profile token, identity token,
     * or password) can be used at a single time. Using this method will clear any previously set authentication
     * information.
     * 
     * @param password The user profile password.
     * @deprecated
     **/
    @Deprecated
    public void setPassword(String password)
    {
        char[] passwordChars = (password == null) ? null : password.toCharArray(); 
        
        try {
            setPassword(passwordChars);
        }
        finally {
            PasswordVault.clearArray(passwordChars);
        }
    }

    /**
     * Sets the password for this object using a char array. The caller is responsible for clearing the array after the
     * method returns. Only one authentication means (Kerberos ticket, profile token, identity token, or password) can
     * be used at a single time. Using this method will clear any previously set authentication information.
     * 
     * @param password The user profile password.
     **/
    public void setPassword(char[] password)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting password.");

        checkPasswordNullAndLength(password, "password");

        synchronized (this)
        {
            credVault_.empty();
            credVault_ = new PasswordVault(password);
            signonInfo_ = null;
        }
    }

    /**
     * Sets the number of days before password expiration to warn the user.
     * 
     * @param days The number of days before expiration to start the warning. Set to -1 to turn off warning.
     **/
    public static void setPasswordExpirationWarningDays(int days)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting the password expiration warning days:", days);
        AS400.expirationWarning = days;
    }

    /**
     * Sets or resets the profile token for this object. Using this method will clear any previously set authentication
     * information.
     * 
     * @param profileToken The profile token.
     **/
    public void setProfileToken(ProfileTokenCredential profileToken)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting profile token.");

        if (profileToken == null)
            throw new NullPointerException("profileToken");

        synchronized (this)
        {
            credVault_.empty();
            credVault_ = new ProfileTokenVault(profileToken);
            signonInfo_ = null;
        }
    }

    /**
     * Sets the name and port of the middle-tier machine where the proxy server is running. If this is not set, then the
     * name is retrieved from the <em>com.ibm.as400.access.AS400.proxyServer</em>
     * <a href="doc-files/SystemProperties.html">system property</a>. The <a href="ProxyServer.html">ProxyServer</a>
     * must be running on the middle-tier machine.
     * <p>
     * The name of the middle-tier machine is ignored in a two-tier environment. If no middle-tier machine is specified,
     * then it is assumed that no middle-tier will be accessed. The name of the middle-tier machine cannot be changed
     * once a connection to this machine has been established.
     * 
     * @param proxyServer The name and port of the proxy server in the format <code>serverName[:port]</code>. If no port
     *                    is specified, a default will be used.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     * @exception PropertyVetoException If any of the registered listeners vetos the property change.
     **/
    public void setProxyServer(String proxyServer) throws PropertyVetoException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting proxy server:", proxyServer);

        if (impl_ != null)
        {
            Trace.log(Trace.ERROR, "Cannot set proxy server after connection has been made.");
            throw new ExtendedIllegalStateException("proxyServer", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }

        if (propertyChangeListeners_ == null && vetoableChangeListeners_ == null)
            proxyServer_ = resolveProxyServer(proxyServer);
        else
        {
            String oldValue = proxyServer_;
            String newValue = resolveProxyServer(proxyServer);

            if (vetoableChangeListeners_ != null)
                vetoableChangeListeners_.fireVetoableChange("proxyServer", oldValue, newValue);

            proxyServer_ = newValue;
            if (propertyChangeListeners_ != null)
                propertyChangeListeners_.firePropertyChange("proxyServer", oldValue, newValue);
        }
    }

    /**
     * Sets the service port in the service port table for the specified service for this system name.
     * 
     * @param service The name of the service. Valid services are:
     *                <ul>
     *                <li>{@link #FILE FILE} - IFS file classes.
     *                <li>{@link #PRINT PRINT} - print classes.
     *                <li>{@link #COMMAND COMMAND} - command and program call classes.
     *                <li>{@link #DATAQUEUE DATAQUEUE} - data queue classes.
     *                <li>{@link #DATABASE DATABASE} - JDBC classes.
     *                <li>{@link #RECORDACCESS RECORDACCESS} - record level access classes.
     *                <li>{@link #CENTRAL CENTRAL} - license management classes.
     *                <li>{@link #SIGNON SIGNON} - sign-on classes.
     *                <li>{@link #HOSTCNN HOSTCNN} - host-connection classes.
     *                </ul>
     * @param port    The port to use for this service. The value {@link #USE_PORT_MAPPER USE_PORT_MAPPER} can be used
     *                to specify that the next connection to this service should ask the port mapper server for the port
     *                number.
     * @exception ExtendedIllegalArgumentException If service or port is not valid.
     * @exception ExtendedIllegalStateException  If system name has not been set.
     **/
    public void setServicePort(int service, int port)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting service port, service " + service + ", port " + port);

        // Validate parameters.
        if (service < 0 || service > 8)
            throw new ExtendedIllegalArgumentException("service (" + service + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);

        if (port < -1)
            throw new ExtendedIllegalArgumentException("port (" + port + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);

        // Validate state.
        if (systemName_.length() == 0 && !systemNameLocal_)
        {
            Trace.log(Trace.ERROR, "Cannot set service port before system name is set.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }

        chooseImpl();
        impl_.setServicePort((systemNameLocal_) ? "localhost" : systemName_, service, port);
    }

    /**
     * Sets the ports in the service port table for all the services for this system name to their default values. This
     * causes the connections to this system name to use the default ports for those services rather than querying the
     * port number through a port mapper connection. The use of this method can reduce the number of connections made to
     * the system.
     * 
     * @exception ExtendedIllegalStateException  If system name has not been set.
     **/
    public void setServicePortsToDefault()
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting service ports to default.");

        // Validate state.
        if (systemName_.length() == 0 && !systemNameLocal_)
        {
            Trace.log(Trace.ERROR, "Cannot set service port to default before system name is set.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }
        chooseImpl();
        impl_.setServicePortsToDefault((systemNameLocal_) ? "localhost" : systemName_);
    }

    /**
     * Indicates if checkboxes should be shown on the sign-on dialog.
     * 
     * @param showCheckboxes true to show checkboxes; false otherwise.
     **/
    public void setShowCheckboxes(boolean showCheckboxes)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting show checkboxes:", showCheckboxes);
        showCheckboxes_ = showCheckboxes;
    }

    /**
     * Sets the sign-on handler for this AS400 object. The specified handler will be called at runtime if the AS400
     * object needs to obtain additional signon information. <br>
     * By default, an internal AWT-based implementation is used, if no sign-on handler has been statically set via
     * setDefaultSignonHandler().
     * 
     * @param handler The sign-on handler. Specifying <tt>null</tt> will reset the default sign-on handler to the
     *                internal AWT-based handler.
     * @see #getSignonHandler
     * @see #setDefaultSignonHandler
     **/
    public void setSignonHandler(SignonHandler handler)
    {
        if (Trace.traceOn_)
        {
            if (handler == null) Trace.log(Trace.DIAGNOSTIC, "Setting the sign-on handler to null.");
            if (signonHandler_ != null) Trace.log(Trace.DIAGNOSTIC, "Sign-on handler was formerly an instance of " + signonHandler_.getClass().getName());
        }
        signonHandler_ = handler;
    }


    
    private volatile long stayAliveMilliSeconds_ = 0;
    private class StayAliveThread extends Thread
    {
        private boolean stayAliveIsConnectionAlive(int service)
        {
            try {
                return isConnectionAlive(service);
            }
            catch (Exception e) {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Stayalive isConnectionAlive thread exception on service " + service + " ignored. ", e);
            }
            
            return false;
        }
        
        @Override
        public void run()
        {
            // Want to always wake up every 30 seconds if say-alive time greater than that. 
            // This to ensure that we end thread if stay-alive time set to 0. 
            // So when whenToPing is zero or less, we actually ping. Otherwise, we loop. 
            long stayAliveMilliSeconds_original = stayAliveMilliSeconds_;
            long whenToPing = stayAliveMilliSeconds_original;
            long sleepTime = (stayAliveMilliSeconds_original > 30000) ? 30000 : stayAliveMilliSeconds_original;
            
            while (stayAliveMilliSeconds_ > 0)
            {
                try 
                {
                    sleep(sleepTime);
                    whenToPing -= sleepTime;

                    // If stay-alive time changed to zero, let thread end. 
                    if (stayAliveMilliSeconds_ == 0)
                        break;
                    
                    if (whenToPing <= 0)
                    {
                        whenToPing = stayAliveMilliSeconds_original;
                        
                        boolean bDATABASE   =stayAliveIsConnectionAlive(AS400.DATABASE);
                        boolean bCOMMAND    =stayAliveIsConnectionAlive(AS400.COMMAND);
                        boolean bDATAQUEUE  =stayAliveIsConnectionAlive(AS400.DATAQUEUE);
                        boolean bFILE       =stayAliveIsConnectionAlive(AS400.FILE);
                        boolean bPRINT      =stayAliveIsConnectionAlive(AS400.PRINT);
                        boolean bSIGNON     =stayAliveIsConnectionAlive(AS400.SIGNON);
                        boolean bHOSTCNN    =stayAliveIsConnectionAlive(AS400.HOSTCNN);
                        
                        if (Trace.traceOn_) 
                        {
                            Trace.log(Trace.DIAGNOSTIC, "Stayalive status of services: " 
                                    + "DATABASE=" + bDATABASE + ", "
                                    + "COMMAND=" + bCOMMAND + ", "
                                    + "DATAQUEUE=" + bDATAQUEUE + ", "
                                    + "FILE=" + bFILE + ", "
                                    + "PRINT=" + bPRINT + ", "
                                    + "SIGNON=" + bSIGNON + ", "
                                    + "HOSTCNN=" + bHOSTCNN);
                        }
                    }
                    
                    if (stayAliveMilliSeconds_original != stayAliveMilliSeconds_)
                    {
                        stayAliveMilliSeconds_original = stayAliveMilliSeconds_;
                        whenToPing = stayAliveMilliSeconds_original;
                        sleepTime = (stayAliveMilliSeconds_original > 30000) ? 30000 : stayAliveMilliSeconds_original;
                    }
                }
                catch (Exception e) {
                    if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Stayalive thread exception ignored. ", e);
                }
            }
        }
    }
    private StayAliveThread stayAliveThread_;
    
    /**
     * Set the stay-alve interval. When enabled, a request is sent at the specified seconds interval to all
     * currently opened connections to help keep the connections alive. This is sometimes needed to prevent firewalls
     * from dropping stale connections.  
     * <P>
     * This stay-alive functionality only applies to connections to the following host servers: {@link #COMMAND
     * COMMAND}, {@link #DATABASE DATABASE}, {@link #DATAQUEUE DATAQUEUE}, {@link #FILE FILE}, {@link #PRINT PRINT}, 
     * {@link #HOSTCNN HOSTCNN}, and {@link #SIGNON SIGNON}.
     * <p>
     * <b>Notes:</b>
     * <ul>
     * <li>A background thread is used to ensure the connections stay alive. Any errors that occur while attempting
     * to ensure connections are alive are ignored. 
     * <li>If {@link #resetAllServices()} is called on the object, the number of seconds is set to zero.  You will 
     *     need to invoke setStayAlive() method to re-enable the stay-alive functionality. 
     * <li>If the AS400 object is used in constructor to create another AS400 object, the newly created AS400 object
     *     will not inherit the stay-alive value set for the object.
     * </ul>
     * 
     * @param seconds The number of seconds between requests to the server. If set to zero, then this
     *                     stay-alive capability will not be used. 
     *                     
     * @exception ExtendedIllegalArgumentException  if the value of seconds is negative.
     **/
    synchronized public void setStayAlive(long seconds)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting stay-alive: " + seconds);
                
        // Validate parameter. 
        if (seconds < 0)
            throw new ExtendedIllegalArgumentException("seconds (" + seconds + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);

        stayAliveMilliSeconds_ = seconds*1000;
        
        if (stayAliveMilliSeconds_ == 0)
            stayAliveThread_ = null;
        else if (stayAliveThread_ == null || !stayAliveThread_.isAlive())
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Creating stay-alive thread.");

            stayAliveThread_ = new StayAliveThread();
            stayAliveThread_.setDaemon(true);
            stayAliveThread_.start();
        }
    }
    
    /**
     * Returns the stay-alve interval for the AS400 object. See {@link #setStayAlive(long)} for 
     * further information.
     *  
     * @return The stay-alive interval in seconds. 
     */
    public long getStayAlive() {
        return (stayAliveMilliSeconds_ / 1000);
    }

    /**
     * Sets the socket options the IBM Toolbox for Java will set on its client side sockets. The socket properties
     * cannot be changed once a connection to the system has been established.
     * 
     * @param socketProperties The set of socket options to set. The options are copied from this object, not shared.
     * 
     * @exception ExtendedIllegalStateException  if a connection has already been made.
     **/
    public void setSocketProperties(SocketProperties socketProperties)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting socket properties: " + socketProperties);
        if (socketProperties == null)
            throw new NullPointerException("socketProperties");

        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set socket properties after connection has been made.");
            throw new ExtendedIllegalStateException("socketProperties", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }
        socketProperties_.copyValues(socketProperties);
    }

    /**
     * Sets the system name for this object. The system name cannot be changed once a connection to the system has been
     * established.
     * 
     * @param systemName The name of the IBM i system. Use <code>localhost</code> to access data locally.
     * @exception ExtendedIllegalStateException  if a connection has already been made.
     * @exception PropertyVetoException If any of the registered listeners vetos the property change.
     **/
    public void setSystemName(String systemName) throws PropertyVetoException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting system name:", systemName);
        if (systemName == null)
            throw new NullPointerException("systemName");

        if (systemName.equals(systemName_)) return;
        if (propertiesFrozen_ && (systemNameLocal_ || systemName_.length() != 0))
        {
            Trace.log(Trace.ERROR, "Cannot set system name after connection has been made.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }

        if (propertyChangeListeners_ == null && vetoableChangeListeners_ == null)
        {
            systemName_ = systemName;
            systemNameLocal_ = resolveSystemNameLocal(systemName);
        }
        else
        {
            String oldValue = systemName_;
            String newValue = systemName;

            if (vetoableChangeListeners_ != null)
                vetoableChangeListeners_.fireVetoableChange("systemName", oldValue, newValue);

            systemName_ = systemName;
            systemNameLocal_ = resolveSystemNameLocal(systemName);
            if (propertyChangeListeners_ != null)
                propertyChangeListeners_.firePropertyChange("systemName", oldValue, newValue);
        }
    }

    /**
     * Sets whether the IBM Toolbox for Java uses threads in communication with the host servers. The default is true.
     * Letting the IBM Toolbox for Java use threads may be beneficial to performance, turning threads off may be
     * necessary if your application needs to be compliant with the Enterprise Java Beans specification. The thread used
     * property cannot be changed once a connection to the system has been established.
     * 
     * <p>
     * Note: This property may also be set by specifying 'true' or 'false' in Java system property
     * <tt>com.ibm.as400.access.AS400.threadUsed</tt>
     * 
     * @param useThreads true to use threads; false otherwise.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     * @exception PropertyVetoException If any of the registered listeners vetos the property change.
     **/
    public void setThreadUsed(boolean useThreads) throws PropertyVetoException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting thread used:", useThreads);

        if (propertiesFrozen_)
        {
            Trace.log(Trace.ERROR, "Cannot set thread used after connection has been made.");
            throw new ExtendedIllegalStateException("threadUsed", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }

        if (propertyChangeListeners_ == null && vetoableChangeListeners_ == null)
            threadUsed_ = useThreads;
        else
        {
            Boolean oldValue = Boolean.valueOf(threadUsed_);
            Boolean newValue = Boolean.valueOf(useThreads);

            if (vetoableChangeListeners_ != null)
                vetoableChangeListeners_.fireVetoableChange("threadUsed", oldValue, newValue);

            threadUsed_ = useThreads;
            if (propertyChangeListeners_ != null)
                propertyChangeListeners_.firePropertyChange("threadUsed", oldValue, newValue);
        }
    }

    /**
     * Sets the indicator for whether the default user is used. The default user is used if a system name is provided,
     * but a user ID is not. If a default user is set for that system, then the default user is used.
     * 
     * @param useDefaultUser The value indicating if the default user should be used. Set to true if default user should
     *                       be used; false otherwise. The default is true, indicating that the default user is used.
     * @exception PropertyVetoException If any of the registered listeners vetos the property change.
     **/
    public void setUseDefaultUser(boolean useDefaultUser) throws PropertyVetoException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting use default user:", useDefaultUser);

        if (propertyChangeListeners_ == null && vetoableChangeListeners_ == null)
            useDefaultUser_ = useDefaultUser;
        else
        {
            Boolean oldValue = Boolean.valueOf(useDefaultUser_);
            Boolean newValue = Boolean.valueOf(useDefaultUser);

            if (vetoableChangeListeners_ != null)
                vetoableChangeListeners_.fireVetoableChange("useDefaultUser", oldValue, newValue);

            useDefaultUser_ = useDefaultUser;
            if (propertyChangeListeners_ != null)
                propertyChangeListeners_.firePropertyChange("useDefaultUser", oldValue, newValue);
        }
    }

    /**
     * Sets the indicator for whether the password cache is used. If password cache is used, then the user would only
     * have to enter password once within a Java virtual machine. The default is to use the cache. <br>
     * Unless the application is running in its own private JVM, users are advised to turn off password caching, in
     * order to ensure that another application within the same JVM cannot create a connection using the cached
     * password.
     * 
     * @param usePasswordCache The value indicating whether the password cache should be used. Set to true to use the
     *                         password cache; false otherwise.
     * @exception PropertyVetoException If any of the registered listeners vetos the property change.
     **/
    public void setUsePasswordCache(boolean usePasswordCache) throws PropertyVetoException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting use password cache:", usePasswordCache);

        if (propertyChangeListeners_ == null && vetoableChangeListeners_ == null)
            usePasswordCache_ = usePasswordCache;
        else
        {
            Boolean oldValue = Boolean.valueOf(usePasswordCache_);
            Boolean newValue = Boolean.valueOf(usePasswordCache);

            if (vetoableChangeListeners_ != null)
                vetoableChangeListeners_.fireVetoableChange("usePasswordCache", oldValue, newValue);

            usePasswordCache_ = usePasswordCache;
            if (propertyChangeListeners_ != null)
                propertyChangeListeners_.firePropertyChange("usePasswordCache", oldValue, newValue);
        }
    }

    /**
     * Sets the user ID for this object. The user ID cannot be changed once a connection to the system has been
     * established. If this method is used in conjunction with a Kerberos ticket, profile token, or identity token, the
     * user profile associated with the authentication token must match this user ID.
     * 
     * @param userId The user profile name.
     * @exception ExtendedIllegalArgumentException If userId length is not valid.
     * @exception ExtendedIllegalStateException  If a connection has already been made.
     * @exception PropertyVetoException If any of the registered listeners vetos the property change.
     **/
    public void setUserId(String userId) throws PropertyVetoException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting user ID: '" + userId + "'");
        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.equals(userId_)) return;
        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        if (signonInfo_ != null)
        {
            Trace.log(Trace.ERROR, "Cannot set user ID after connection has been made.");
            throw new ExtendedIllegalStateException("userId", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
        }
        
        if (isTurkish()) {
            userId = userId.toUpperCase(Locale.ENGLISH);
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }

        if (propertyChangeListeners_ == null && vetoableChangeListeners_ == null)
            userId_ = userId.toUpperCase();
        else
        {
            String oldValue = userId_;
            String newValue = userId.toUpperCase();

            if (vetoableChangeListeners_ != null)
                vetoableChangeListeners_.fireVetoableChange("userId", oldValue, newValue);

            userId_ = newValue;
            if (propertyChangeListeners_ != null)
                propertyChangeListeners_.firePropertyChange("userId", oldValue, newValue);
        }
    }

    // Initiate sign-on to the system.  This method is synchronized to prevent more than one thread from needlessly signing-on.  
    // This method can safely be called multiple times because it checks for a previous sign-on before performing the sign-on code.
    synchronized void signon(boolean keepConnection) throws AS400SecurityException, IOException
    {
        // If we haven't already signed on.
        if (signonInfo_ == null)
        {
            chooseImpl();
            userId_ = resolveUserId(userId_, credVault_.getType(), mustUseSuppliedProfile_);
            // If system name is set.
            if (systemName_.length() != 0)
            {
                // If user ID is not set and we can use the default user.
                if (userId_.length() == 0 && useDefaultUser_)
                {
                    // Get the default user ID.
                    String defaultUserId = getDefaultUser(systemName_);
                    // If we have a default user ID for this system, set the user ID to it.
                    if (defaultUserId != null) userId_ = defaultUserId;
                }
                
                // If the user ID is set and the password is not set and we can use the password cache.
                if (userId_.length() != 0 && credVault_.isEmpty() && usePasswordCache_)
                {
                    // Get password from password cache.
                    synchronized (AS400.systemList)
                    {
                        for (int i = AS400.systemList.size() - 1; i >= 0; i--)
                        {
                            Object[] secobj = (Object[])AS400.systemList.elementAt(i);
                            if (systemName_.equalsIgnoreCase((String)secobj[0]) && userId_.equals(secobj[1]))
                            {
                              // We are taking an entry from the password cache
                              // and using it for our own AS400 object.
                              // Thus we must take a copy of it, to maintain
                              // the protocol of never using a credential vault
                              // that someone else has a reference to.
                              // Note that we do not need to bother emptying
                              // our existing vault; the if-check above has
                              // assured us that our vault is currently empty.
                              credVault_ = (CredentialVault)((CredentialVault)secobj[2]).clone();
                            }
                        }
                    }
                }
            }

            try
            {
                // If the system name is set, we're not using proxy, and the password is not set, and the user has not told us not to.
                if (systemName_.length() != 0 && proxyServer_.length() == 0 && credVault_.isEmpty() 
                        && (credVault_.getType() == AUTHENTICATION_SCHEME_GSS_TOKEN || gssOption_ != AS400.GSS_OPTION_NONE))
                {
                    // Try for Kerberos.
                    byte[] newBytes = (gssCredential_ == null) ? TokenManager.getGSSToken(systemName_, gssName_) :
                      TokenManager2.getGSSToken(systemName_, gssCredential_);

                    // We do not have to empty the existing vault because the
                    // previous if-check assures us it is already empty.
                    credVault_ = new GSSTokenVault(newBytes);
                }
            }
            catch (Throwable e)
            {
                if (credVault_.getType() == AUTHENTICATION_SCHEME_GSS_TOKEN || gssOption_ == AS400.GSS_OPTION_MANDATORY)
                {
                    Trace.log(Trace.ERROR, "Error retrieving GSSToken:", e);
                    throw new AS400SecurityException(AS400SecurityException.KERBEROS_TICKET_NOT_VALID_RETRIEVE,e);
                }
                else
                {  // Tolerate the exception - we don't require GSS.
                    Trace.log(Trace.DIAGNOSTIC, "GSSToken is not available:", e.getMessage());
                    Trace.log(Trace.DIAGNOSTIC, "Tolerated exception retrieving GSSToken:", e);
                }
            }

            // Note: A user-supplied sign-on handler isn't necessarily GUI-based.
            promptSignon();
            if (!keepConnection) {
                if (impl_.isConnected(AS400.SIGNON)) {
                    if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Disconnecting temporary connection for validating signon.");
                    impl_.disconnect(AS400.SIGNON);
                }
            }
        }
    }

    /**
     * Returns the text representation of this AS400 object.
     * 
     * @return The string representing this AS400 object.
     **/
    public String toString() {
        return "AS400 (system name: '" + systemName_ + "' user ID: '" + userId_ + "'):" + super.toString();
    }

    // Determine if user ID matches current user ID.
    private static boolean userIdMatchesLocal(String userId, boolean mustUseSuppliedProfile)
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Determining if specified userID ("+userId+") matches current userID.");

        boolean result;

        // First, see if we are running on IBM i.
        if (AS400.onAS400 && !mustUseSuppliedProfile && currentUserAvailable())
        {
            String currentUserID = CurrentUser.getUserID(AS400.nativeVRM.getVersionReleaseModification());
            if (currentUserID == null)
            {
                if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Current userID information not available.");
                result = false;
            }
            else
            {
                result = userId.equals(currentUserID);
                if (Trace.traceOn_ && !result)
                    Trace.log(Trace.DIAGNOSTIC, "Specified userID ("+userId+") does not match current userID ("+currentUserID+").");
            }
        }
        else
        {
            result = false;
            if (Trace.traceOn_)
            {
                if (!AS400.onAS400) Trace.log(Trace.DIAGNOSTIC, "Not running on IBM i.");
                if (mustUseSuppliedProfile) Trace.log(Trace.DIAGNOSTIC, "Caller specified must use supplied profile.");
                if (!currentUserAvailable()) Trace.log(Trace.DIAGNOSTIC, "Class CurrentUser is not available.");
            }
        }

        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Determined that specified userID ("+userId+") " + (result ? "matches" : "does not match") + " current userID.");

        return result;
    }

    /**
     * Validates the user ID and password on the system but does not add to the signed-on list. This means that a new
     * AS400 object instance is created to do the validation. The user ID and system name need to be set before calling
     * this method.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * <p>
     * <b>Note:</b>If an additional authentication factor has been set in the AS400 object, it is used.
     * 
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception ExtendedIllegalStateException  If system name or user ID is not set.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public boolean validateSignon() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Validating Signon, no parameters.");

        if (systemName_.length() == 0 && !systemNameLocal_)
        {
            Trace.log(Trace.ERROR, "Cannot validate signon before system name is set.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }
        
        userId_ = resolveUserId(userId_);
        if (userId_.length() == 0)
        {
            Trace.log(Trace.ERROR, "Cannot validate signon before user ID is set.");
            throw new ExtendedIllegalStateException("userId", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }
        return validateSignon(true, userId_, credVault_, additionalAuthenticationFactor_);
    }

    /**
     * Validates the user ID and password on the system but does not add to the signed-on list. This means that a new
     * AS400 object instance is created to do the validation. The user ID and system name need to be set before calling
     * this method.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * <p>
     * <b>Note:</b>If an additional authentication factor has been set in the AS400 object, it is used.
     * 
     * @param password The user profile password to validate.
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @deprecated
     **/
    @Deprecated
    public boolean validateSignon(String password) throws AS400SecurityException, IOException
    {
        char[] passwordChars = (password == null) ? null : password.toCharArray();
        
        try {
           return validateSignon(passwordChars);
        }
        finally {
            PasswordVault.clearArray(passwordChars);
        }
    }

    /**
     * Validates the user ID and password on the system but does not add to the signed-on list. This means that a new
     * AS400 object instance is created to do the validation. The user ID and system name need to be set before calling
     * this method.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * <p>
     * <b>Note:</b>If an additional authentication factor has been set in the AS400 object, it is used.
     * 
     * @param password The user profile password to validate.
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception ExtendedIllegalStateException  If system name or user ID is not set.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public boolean validateSignon(char[] password) throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Validating Signon, with password.");
        
        checkPasswordNullAndLength(password, "password"); 
        if (systemName_.length() == 0 && !systemNameLocal_)
        {
            Trace.log(Trace.ERROR, "Cannot validate signon before system name is set.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }
        
        userId_ = resolveUserId(userId_);
        if (userId_.length() == 0)
        {
            Trace.log(Trace.ERROR, "Cannot validate signon before user ID is set.");
            throw new ExtendedIllegalStateException("userId", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }

        PasswordVault tempVault = new PasswordVault(password);
        return validateSignon(true, userId_, tempVault, additionalAuthenticationFactor_);
    }
      
    /**
     * Validates the user ID and password on the system but does not add to the signed-on list. This means that a new
     * AS400 object instance is created to do the validation. The system name needs to be set prior to calling this
     * method.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * <p>
     * <b>Note:</b>If an additional authentication factor has been set in the AS400 object, it is not used. If you want
     * to use an additional authentication factor, see {@link #validateSignon(String, char[], char[])}
     * 
     * @param userId   The user profile name to validate.
     * @param password The user profile password to validate.
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     * @deprecated Using a string as a password is insecure
     **/
    @Deprecated
    public boolean validateSignon(String userId, String password) throws AS400SecurityException, IOException
    {
        char[] passwordChars = (password == null) ? null : password.toCharArray();
        
        try {
            return validateSignon(userId, passwordChars, null);
        }
        finally {
            PasswordVault.clearArray(passwordChars);
        }
    }

    /**
     * Validates the user ID and password on the system but does not add to the signed-on list. This means that a new
     * AS400 object instance is created to do the validation. The system name needs to be set prior to calling this
     * method.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * <p>
     * <b>Note:</b>If an additional authentication factor has been set in the AS400 object, it is not used. If you want
     * to use an additional authentication factor, see {@link #validateSignon(String, char[], char[])}
     * 
     * @param userId   The user profile name to validate.
     * @param password The user profile password to validate.
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public boolean validateSignon(String userId, char[] password) throws AS400SecurityException, IOException
    {
        return validateSignon(userId, password, null);
    }

    /**
     * Validates the user ID and password on the system but does not add to the signed-on list. This means that a new
     * AS400 object instance is created to do the validation. The system name needs to be set prior to calling this
     * method.
     * <p>
     * <b>Note:</b> This will return true if the information is successfully validated. An unsuccessful validation will
     * cause an exception to be thrown, false is never returned.
     * 
     * @param userId               The user profile name to validate.
     * @param password             The user profile password to validate.
     * @param additionalAuthFactor Additional authentication factor (or null if not providing one).
     * @return true if successful.
     * @exception AS400SecurityException If a security or authority error occurs.
     * @exception ExtendedIllegalArgumentException If userId or additionalAuthFactor length is not valid.
     * @exception ExtendedIllegalStateException  If system name or is not set.
     * @exception IOException            If an error occurs while communicating with the system.
     **/
    public boolean validateSignon(String userId, char[] password, char[] additionalAuthFactor) throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Validating signon, password and additional factor, user ID: '" + userId + "'");

        if (userId == null)
            throw new NullPointerException("userId");

        if (userId.length() > 10)
            throw new ExtendedIllegalArgumentException("userId (" + userId + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
        
        if (additionalAuthFactor != null && additionalAuthFactor.length > ProfileTokenCredential.MAX_ADDITIONALAUTHENTICATIONFACTOR_LENGTH)
            throw new ExtendedIllegalArgumentException("additionalAuthFactor", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);

        checkPasswordNullAndLength(password, "password");
 
        if (systemName_.length() == 0 && !systemNameLocal_)
        {
            Trace.log(Trace.ERROR, "Cannot validate signon before system name is set.");
            throw new ExtendedIllegalStateException("systemName", ExtendedIllegalStateException.PROPERTY_NOT_SET);
        }

        PasswordVault tempVault = new PasswordVault(password);

        if (isTurkish()) {
          userId = userId.toUpperCase(Locale.ENGLISH);
          if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "This system locale is Turkish, userId.toUpperCase(Locale.ENGLISH)");
        }
        return validateSignon(true, userId.toUpperCase(), tempVault, additionalAuthFactor);
    }
    
    // Internal version of validate sign-on; takes checked user ID and twiddled password bytes.
    // If the signon info is not valid, an exception is thrown.
    private boolean validateSignon(boolean useNewInstance, String userId, CredentialVault pwVault, char[] additionalAuthFactor) throws AS400SecurityException, IOException
    {
         // Use a new instance when system, userid, and credentials are not part of this
        // instance of AS400 class;  otherwise, use this instance. 
        if (useNewInstance)
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Creating temporary connection for validating signon info.");

            try (AS400 validationSystem = new AS400(systemName_, userId, pwVault, additionalAuthFactor))
            {
                validationSystem.proxyServer_ = proxyServer_;
                // proxyClientConnection_ is not needed.
                validationSystem.guiAvailable_ = false;
                validationSystem.usePasswordCache_ = false;
                validationSystem.useDefaultUser_ = false;
                // showCheckboxes_ is not needed.
                validationSystem.useSSLConnection_ = useSSLConnection_;
                validationSystem.mustUseSockets_ = true; // force the use of the Signon Server
                validationSystem.mustUseNetSockets_ = mustUseNetSockets_;
                validationSystem.mustUseSuppliedProfile_ = mustUseSuppliedProfile_;
                // threadUsed_ is not needed.
                // locale_ in not needed.
                // nlv_ in not needed.
                validationSystem.socketProperties_.copyValues(socketProperties_);
                if (validateSignonTimeOut_ > 0)
                    validationSystem.socketProperties_.setSoTimeout(validateSignonTimeOut_);
                // ccsid_ is not needed.
                // connectionListeners_ is not needed.
                // dispatcher_ is not needed.
                // propertyChangeListeners_ is not needed.
                // vetoableChangeListeners_ is not needed.
                // propertiesFrozen_ is not needed.
                // impl_ is not copied.
                // signonInfo_ is not copied.
    
                validationSystem.signon(false); // signon(false) calls disconnect() when done
            }
        }
        else
        {
            if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Using AS400 object for validating signon.");

            signon(false);
        }
        
        return true;
    }

    private void checkPasswordNullAndLength(char[] password, String label)
    { 
      if (password == null)
          throw new NullPointerException(label);

      if (password.length > 128)
          throw new ExtendedIllegalArgumentException(label+".length {" + password.length + ")", ExtendedIllegalArgumentException.LENGTH_NOT_VALID);
    }
    
    /**
    This tells AS400 to force prompt by displaying login dialog (actually the sign-on handler) prior to even trying to authenticate.
    This is useful in cases where an application sends in incorrect dummy id/password and expects Toolbox to display the logon dialog.
    In JDBC, we do some pre-validation of id/password.  So JDBC may flag the id/password as invalid and then need
    to let AS400 know that it just needs to display the logon dialog.
    **/
    void forcePrompt()
    {
        forcePrompt_ = true;
    }

    private int bidiStringType = BidiStringType.DEFAULT;

    /**
     * Sets bidi string type of the connection. See <a href="BidiStringType.html">BidiStringType</a> for more
     * information and valid values.
     * 
     * @param bidiStringType bidi string type to use for the connection.
     */
    public void setBidiStringType(int bidiStringType){
        this.bidiStringType = bidiStringType;
    }

    /**
     * Returns bidi string type of the connection. See <a href="BidiStringType.html">BidiStringType</a> for more
     * information and valid values.
     * 
     * @return The bidi string type of the connection.
     */
    public int getBidiStringType(){
        return bidiStringType;
    }

    /**
     * Determines whether Bidi processing should occur in AS400Text.toBytes() method
     */
    public boolean bidiAS400Text = false;
    
    /**
     * Determines whether Bidi processing should occur in AS400Varchar.toBytes() method
     */
    public boolean bidiAS400Varchar = false;

    // Set the signon information for the connection
    // Typicially used when the signon server has been skipped and the information
    // is retrieved from a different host server
    void setSignonInfo(int serverCCSID, int serverVersion, String userId)
    {
        if (signonInfo_ == null)
            signonInfo_ = new SignonInfo();

        signonInfo_.serverCCSID = serverCCSID;
        signonInfo_.version = new ServerVersion(serverVersion);
        signonInfo_.userId = userId;
        ccsid_ = signonInfo_.serverCCSID;
    }
    
    /**
     * Return whether default locale is Turkish. 
     * 
     * @return true if Turkish; otherwise, false.
     */
    public static boolean isTurkish()
    {
        Locale defaultLocale = Locale.getDefault();
        Locale Turkishlocale = new Locale("tr", "TR", "");
        
        return (defaultLocale.equals(Turkishlocale) ) ? true : false;
    }
    
    /**
     * Returns the timeout value when attempting to validate the sign on information.
     * 
     * @return The timeout value in milliseconds.
     */
    public int getvalidateSignonTimeOut() {
        return validateSignonTimeOut_;
    }
    
    /**
     * Set the the timeout value when when attempting to validate the sign on information.
     * 
     * @param validateSignonTimeOut The timeout value in milliseconds.
     */
    public void setvalidateSignonTimeOut(int validateSignonTimeOut) {
        validateSignonTimeOut_ = validateSignonTimeOut;
    }
    
    /**
     * Password level
     * 
     * @return -1 - Not remote method, such as proxy method. 0, 1 - password type is DES 2, 3 - password type is SHA-1 4
     *         - password type is SHA-512
     * @throws AS400SecurityException If a security or authority error occurs.
     * @throws IOException            If an error occurs while communicating with the system.
     */
    public int passwordLevel() throws AS400SecurityException, IOException
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Checking if use password phrase");
        
        chooseImpl();
        signon(false);
        int pwdlvl = -1;
        if (impl_ instanceof AS400ImplRemote)
            pwdlvl = ((AS400ImplRemote)impl_).getPasswordLevel();
        
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Use password level: " + pwdlvl);

        return pwdlvl;
    }

    /**
     * This method is functionally equivalent to the <i>resetAllServices()</i> method.
     * 
     * @see #resetAllServices
     **/
    @Override
    public void close() {
        // resetAllServices() ensures that HOSTCNN is disconnected from this AS400.  
        // disconnectAllServices() does not disconnect HOSTCNN connection.
        resetAllServices();
    }
    
    // Method to ensure operations for secure AS400 instances are enforced.
    private void ensureSecureInstance()
    {
        if (!isSecure())
        {
            Trace.log(Trace.ERROR, "Cannot perform opertation on non-secure AS400 object. ");
            throw new ExtendedIllegalStateException(ExtendedIllegalStateException.IMPLEMENTATION_NOT_FOUND);
        }
    }
    
    
    // Called during garbage collections - Cleans up all connections and daemon threads
    @Override
    protected void finalize() throws Throwable
    {
        if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Finalize method for AS400 invoked.");

        resetAllServices();
    }
    
    /**
     * Returns true if host server communications is performed over a secure channel.
     * <p>
     * <b>Note:</b>This method is the only reliable way to determine whether
     * host server communications is performed over a secure channel. 
     * An AS400 object that is not an instance of SecureAS400 class can use 
     * secure communications in some instances. 
     * 
     * @return true if communications is done over secure channel; otherwise false.
     **/
    public boolean isSecure() {
        return ((useSSLConnection_ != null) || (this instanceof SecureAS400));
    }
    
    // ======== START =================
    // Following chunk of code moved from SecureAS400
    // ======== START =================
    
    /**
     * Returns the key ring class name used for SSL communications with the system. The class
     * <i>com.ibm.as400.access.KeyRing</i> is the default and will be returned if not overridden.
     * <p>
     * <b>Note:</b>An exception will be thrown if the AS400 object is not an instance of SecureAS400.
     * 
     * @return The key ring class name.
     * @deprecated
     **/
    @Deprecated
   public String getKeyRingName()
   {
       ensureSecureInstance();
       
       if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting key ring name: null"    );
       return null; 
   }

   /**
    * Returns the proxy encryption mode. The proxy encryption mode specifies which portions of the communications
    * between the client, proxy server, and IBM i system are encrypted.
    * <p>
    * <b>Note:</b>An exception will be thrown if the AS400 object is not an instance of SecureAS400.
    * 
    * @return The proxy encryption mode.
    **/
   public int getProxyEncryptionMode()
   {
       ensureSecureInstance();
       
       if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Getting proxy encryption mode:", useSSLConnection_.proxyEncryptionMode_);
       return useSSLConnection_.proxyEncryptionMode_;
   }

   /**
    * Sets the key ring class name used for SSL communications with the system. This method is no longer supported
    * because sslight is not longer supported.
    * <p>
    * <b>Note:</b>An exception will be thrown if the AS400 object is not an instance of SecureAS400.
    * 
    * @param keyRingName The key ring class name.
    * @exception ExtendedIllegalStateException  If object is not a secure instance. 
    * @exception PropertyVetoException If any of the registered listeners vetos the property change.
    **/
   public void setKeyRingName(String keyRingName) throws PropertyVetoException
   {
       ensureSecureInstance();

       Trace.log(Trace.ERROR, "Cannot set key ring class name  -- no sslight support ");
       throw new ExtendedIllegalStateException("keyRingName", ExtendedIllegalStateException.IMPLEMENTATION_NOT_FOUND);
   }

   /**
    * Sets the key ring class name used for SSL communications with the system. This method is no longer available since
    * support for sslight has been removed.
    * <p>
    * <b>Note:</b>An exception will be thrown if the AS400 object is not an instance of SecureAS400.
    * 
    * @param keyRingName     The key ring class name.
    * @param keyRingPassword The password for the key ring class.
    * @exception ExtendedIllegalStateException  If object is not a secure instance. 
    * @exception PropertyVetoException If any of the registered listeners vetos the property change.
    **/
   public void setKeyRingName(String keyRingName, String keyRingPassword) throws PropertyVetoException
   {
       ensureSecureInstance();

       Trace.log(Trace.ERROR, "Cannot set key ring class name  -- no sslight support ");
       throw new ExtendedIllegalStateException("keyRingName", ExtendedIllegalStateException.IMPLEMENTATION_NOT_FOUND);
   }

   /**
    * Sets the key ring password used for SSL communications with the system.
    * <p>
    * <b>Note:</b>An exception will be thrown if the AS400 object is not an instance of SecureAS400.
    * 
    * @param keyRingPassword The password for the key ring class.
    * @exception ExtendedIllegalStateException  If object is not a secure instance. 
    * @deprecated
    **/
   @Deprecated
   public void setKeyRingPassword(String keyRingPassword)
   {
       ensureSecureInstance();

       Trace.log(Trace.ERROR, "Cannot set key ring class password.");
       throw new ExtendedIllegalStateException("keyRingPassword", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
   }

   /**
    * Sets the proxy encryption mode. The proxy encryption mode specifies which portions of the communications between
    * the client, proxy server, and IBM i system are encrypted. The default is to encrypt all communications. This value
    * is ignored if a proxy server is not used. <br>
    * Valid proxy encryption modes are: <br>
    * {@link #CLIENT_TO_PROXY_SERVER CLIENT_TO_PROXY_SERVER} - encrypt between client and proxy server. <br>
    * {@link #PROXY_SERVER_TO_SERVER PROXY_SERVER_TO_SERVER} - encrypt between proxy server and IBM i system. <br>
    * {@link #CLIENT_TO_SERVER CLIENT_TO_SERVER} - encrypt both portions of connection.
    * <p>
    * <b>Note:</b>An exception will be thrown if the AS400 object is not an instance of SecureAS400.
    * 
    * @param proxyEncryptionMode The proxy encryption mode.
    * 
    * @exception ExtendedIllegalArgumentException If proxyEncryptionMode value is not valid.
    * @exception ExtendedIllegalStateException  If object is not a secure instance. 
    * @exception PropertyVetoException If any of the registered listeners vetos the property change.
    **/
   public void setProxyEncryptionMode(int proxyEncryptionMode) throws PropertyVetoException
   {
       ensureSecureInstance();

       if (Trace.traceOn_) Trace.log(Trace.DIAGNOSTIC, "Setting proxy encryption mode:", proxyEncryptionMode);
       
       // Validate parameter.
       if (proxyEncryptionMode < CLIENT_TO_PROXY_SERVER ||
           proxyEncryptionMode > CLIENT_TO_SERVER)
       {
           Trace.log(Trace.ERROR, "Value of parameter 'proxyEncryptionMode' is not valid:", proxyEncryptionMode);
           throw new ExtendedIllegalArgumentException("proxyEncryptionMode (" + proxyEncryptionMode + ")", ExtendedIllegalArgumentException.PARAMETER_VALUE_NOT_VALID);
       }
       if (propertiesFrozen_)
       {
           Trace.log(Trace.ERROR, "Cannot set proxy encryption mode after connection has been made.");
           throw new ExtendedIllegalStateException("proxyEncryptionMode", ExtendedIllegalStateException.PROPERTY_NOT_CHANGED);
       }

       Integer oldValue = Integer.valueOf(useSSLConnection_.proxyEncryptionMode_);
       Integer newValue = Integer.valueOf(proxyEncryptionMode);
       if (vetoableChangeListeners_ != null)
         vetoableChangeListeners_.fireVetoableChange("proxyEncryptionMode", oldValue, newValue);

       useSSLConnection_.proxyEncryptionMode_ = proxyEncryptionMode;

       if (propertyChangeListeners_ != null)
         propertyChangeListeners_.firePropertyChange("proxyEncryptionMode", oldValue, newValue);
   }
   
   /**
    * Set list of cipher suites.
    * <p>
    * <b>Note:</b>An exception will be thrown if the AS400 object is not an instance of SecureAS400.
    * 
    * @param suites Array of cipher suites.
    * 
    * @exception ExtendedIllegalStateException  If object is not a secure instance. 
    **/
    public void setEnabledCipherSuites(String[] suites)
    {
        ensureSecureInstance();

        if (suites != null && suites.length > 0)
        {
            changeCipherSuites = true;
            newCipherSuites = suites;
        }
    }
    
    // ======== END =================
    // Previous chunk of code moved from SecureAS400
    // ======== END =================
    
    /**
     * Set the {@link SSLSocketFactory} that will be used when making secure connections.
    * <p>
    * <b>Note:</b>An exception will be thrown if the AS400 object is not an instance of SecureAS400.
    * 
     * @param sslSocketFactory the {@link SSLSocketFactory} to use
     */
    public void setSSLSocketFactory(SSLSocketFactory sslSocketFactory)
    {
        ensureSecureInstance();

        useSSLConnection_.sslSocketFactory_ = sslSocketFactory;
    }

    public String getLocalIPAddress() {
      if (impl_ instanceof AS400ImplRemote) {
        AS400ImplRemote implRemote = (AS400ImplRemote) impl_; 
        return implRemote.getLocalIPAddress(); 
      } else {
        return "127.0.0.1"; 
      }
      
    }
}
