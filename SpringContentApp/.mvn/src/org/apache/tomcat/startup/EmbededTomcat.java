package org.apache.tomcat.startup;

import java.net.*;
import java.io.*;

import org.apache.tomcat.core.*;
import org.apache.tomcat.net.*;
import org.apache.tomcat.request.*;
import org.apache.tomcat.service.*;
import org.apache.tomcat.service.http.*;
import org.apache.tomcat.session.StandardSessionInterceptor;
import org.apache.tomcat.context.*;
import java.security.*;
import javax.servlet.ServletContext;
import java.util.*;

/**
 *  Use this class to embed tomcat in your application.
 *  The order is important:
 *  1. set properties like workDir and debug
 *  2. add all interceptors including your application-specific
 *  3. add the endpoints 
 *  4. add at least the root context ( you can add more if you want )
 *  5. call start(). The web service will be operational.
 *  6. You can add/remove contexts
 *  7. stop().
 *  
 *  You can add more contexts after start, but interceptors and  
 *  endpoints must be set before the first context and root must be
 *  set before start().
 *
 *  All file paths _must_ be absolute. ( right now if the path is relative it
 *  will be made absolute using tomcat.home as base. This behavior is very
 *  "expensive" as code complexity and will be deprecated ).
 * 
 * @author costin@eng.sun.com
 * @author Stefan Freyr Stafansson [stebbi@decode.is]
 */
public class EmbededTomcat { // extends WebService
    ContextManager contextM = null;
    Object application;
    // null == not set up
    Vector requestInt=null;
    Vector contextInt=null;
    /** Right now we assume all web apps use the same
	servlet API version. This will change after we
	finish the FacadeManager implementation
    */
    FacadeManager facadeM=null;
    Vector connectors=new Vector();

    String workDir;
    
    // configurable properties
    int debug=0;
    
    public EmbededTomcat() {
    }

    // -------------------- Properties - set before start
    
    /** Set debugging - must be called before anything else
     */
    public void setDebug( int debug ) {
	this.debug=debug;
    }

    /** This is an adapter object that provides callbacks into the
     *  application.
     *  For tomcat, it will be a RequestInterceptor.
     * 	See the top level documentation
     */
    public void addApplicationAdapter( Object adapter ) {
	if(requestInt==null)  initDefaultInterceptors();

	// In our case the adapter must be RequestInterceptor.
	if ( adapter instanceof RequestInterceptor ) {
	    addRequestInterceptor( (RequestInterceptor)adapter);
	}
    }

    public void setApplication( Object app ) {
	application=app;
    }

    /** Keep a reference to the application in which we are embeded
     */
    public Object getApplication() {
	return application;
    }
    
    public void setWorkDir( String dir ) {
	workDir=dir;
    }
    
    // -------------------- Endpoints --------------------
    
    /** Add a web service on the specified address. You must add all the
     *  endpoints before calling start().
     */
    public void addEndpoint( int port, InetAddress addr , String hostname) {
	if(debug>0) log( "addConnector " + port + " " + addr +
			 " " + hostname );

	PoolTcpConnector sc=new PoolTcpConnector();
	sc.setServer( contextM );
	sc.setDebug( debug );
	sc.setAttribute( "vhost_port" , new Integer( port ) );
	if( addr != null ) sc.setAttribute( "vhost_address", addr );
	if( hostname != null ) sc.setAttribute( "vhost_name", hostname );
	
	sc.setTcpConnectionHandler( new HttpConnectionHandler());
	
	contextM.addServerConnector(  sc );
    }

    /** Add a secure web service without client authentication using the
     * default server socket factory.
     */
    public void addSecureEndpoint( int port, InetAddress addr, String hostname,
				    String keyFile, String keyPass )
    {
        addSecureEndpoint(port, addr, hostname, keyFile, keyPass, false);
    }

    /** Add a secure web service using the
     * org.apache.tomcat.net.SSLSocketFactory.  clientAuth specifies whether
     * client authentication is required or not.
     */
    public void addSecureEndpoint(int port, InetAddress addr, String hostname,
                                  String keyStore, String keyPass,
                                  boolean clientAuth)
    {
	if(debug>0) log( "addSecureConnector " + port + " " + addr + " " +
			 hostname );

	PoolTcpConnector sc=new PoolTcpConnector();
	sc.setServer( contextM );
	contextM.setSecurePort( port );
	sc.setAttribute( "vhost_port" , new Integer( port ) );
	if( addr != null ) sc.setAttribute( "vhost_address", addr );
	if( hostname != null ) sc.setAttribute( "vhost_name", hostname );
        if (keyStore != null)
            sc.setAttribute("keystore", keyStore);
        if (keyPass != null)
            sc.setAttribute("keypass", keyPass);
        if (clientAuth)
            sc.setAttribute("clientAuth", "true");
        sc.setSocketFactory(new org.apache.tomcat.net.SSLSocketFactory());
	//	System.out.println("XXX " + keyFile + " " + keyPass);
	HttpConnectionHandler ch=new HttpConnectionHandler();
	ch.setSecure(true);
	sc.setTcpConnectionHandler( ch );
	// XXX add the secure socket
	
	contextM.addServerConnector(  sc );
    }

    /** Add a custom web service using the specified socket factory.
     *
     * @param port Port number on which to listen
     * @param addr Internet address on which to listen
     * @param hostname Virtual host name for this service
     * @param secure Should this endpoint be marked secure?
     * @param socketFactory The factory for server sockets to be used
     */
    public void addCustomEndpoint(int port, InetAddress addr, String hostname,
                                  boolean secure,
                                  ServerSocketFactory socketFactory) {
        if (debug>0) log("addCustomEndpoint " + port + " " + addr + " " +
                         hostname);

        PoolTcpConnector sc = new PoolTcpConnector();
        sc.setServer(contextM);
        if (secure) contextM.setSecurePort(port);
        sc.setAttribute("vhost_port", new Integer(port));
        if (addr != null) sc.setAttribute("vhost_address", addr);
        if (hostname != null) sc.setAttribute("vhost_name", hostname);
        sc.setSocketFactory(socketFactory);
        HttpConnectionHandler ch = new HttpConnectionHandler();
        ch.setSecure(secure);
        sc.setTcpConnectionHandler(ch);
        contextM.addServerConnector(sc);

    }

    // -------------------- Context add/remove --------------------
    
    /** Add and init a context
     */
    public ServletContext addContext( String ctxPath, URL docRoot ) {
	if(debug>0) log( "add context \"" + ctxPath + "\" " + docRoot );
	if( contextM == null )
	    initContextManager();
	
	// tomcat supports only file-based contexts
	if( ! "file".equals( docRoot.getProtocol()) ) {
	    log( "addContext() invalid docRoot: " + docRoot );
	    throw new RuntimeException("Invalid docRoot " + docRoot );
	}

	try {
	    Context ctx=new Context();
	    ctx.setDebug( debug );
	    ctx.setContextManager( contextM );
	    ctx.setPath( ctxPath );
	    // XXX if virtual host set it.
	    ctx.setDocBase( docRoot.getFile());
	    contextM.addContext( ctx );
	    if( facadeM == null ) facadeM=ctx.getFacadeManager();
	    return ctx.getFacade();
	} catch( Exception ex ) {
	    ex.printStackTrace();
	}
	return null;
    }

    /** Remove a context
     */
    public void removeContext( ServletContext sctx ) {
	if(debug>0) log( "remove context " + sctx );
	try {
	    if( facadeM==null ) {
		System.out.println("XXX ERROR: no facade manager");
		return;
	    }
	    Context ctx=facadeM.getRealContext( sctx );
	    contextM.removeContext( ctx );
	} catch( Exception ex ) {
	    ex.printStackTrace();
	}
    }

    Hashtable extraClassPaths=new Hashtable();

    /** The application may want to add an application-specific path
	to the context.
    */
    public void addClassPath( ServletContext context, String cpath ) {
	if(debug>0) log( "addClassPath " + context.getRealPath("") + " " +
			  cpath );

	try {
	    Vector cp=(Vector)extraClassPaths.get(context);
	    if( cp == null ) {
		cp=new Vector();
		extraClassPaths.put( context, cp );
	    }
	    cp.addElement( cpath );
	} catch( Exception ex ) {
	    ex.printStackTrace();
	}
	
	// XXX This functionality can be achieved by setting it in the parent
	// class loader ( i.e. the loader that is used to load tomcat ).

	// It shouldn't be needed if the web app is self-contained,
    }

    /** Find the context mounted at /cpath.
	Right now virtual hosts are not supported in
	embeded tomcat.
    */
    public ServletContext getServletContext( String host,
					     String cpath )
    {
	// We don't support virtual hosts in embeded tomcat
	// ( it's not difficult, but can be done later )
	Context ctx=contextM.getContext( cpath );
	if( ctx==null ) return null;
	return ctx.getFacade();
    }

    /** This will make the context available.
     */
    public void initContext( ServletContext sctx ) {
	try {
	    if( facadeM==null ) {
		System.out.println("XXX ERROR: no facade manager");
		return;
	    }
	    Context ctx=facadeM.getRealContext( sctx );
	    contextM.initContext( ctx );

	    ServletLoader sl=ctx.getServletLoader();
	    //	    System.out.println("ServletLoader: " + sl );
	    Object pd=ctx.getProtectionDomain();
	    //	    System.out.println("Ctx.pd " + pd);

	    // Add any extra cpaths
	    Vector cp=(Vector)extraClassPaths.get( sctx );
	    if( cp!=null ) {
		for( int i=0; i<cp.size(); i++ ) {
		    String cpath=(String)cp.elementAt(i);
		    sl.addRepository( new File(cpath), pd);
		}
	    }


	} catch( Exception ex ) {
	    ex.printStackTrace();
	}
    }

    public void destroyContext( ServletContext ctx ) {

    }

    // -------------------- Start/stop
    
    public void start() {
	try {
	    contextM.start();
	} catch( IOException ex ) {
	    System.out.println("Error starting endpoing " + ex.toString());
	} catch( Exception ex ) {
	    ex.printStackTrace();
	}
	if(debug>0) log( "Started" );
    }

    public void stop() {
	// XXX not implemented
    }
    
    // -------------------- Private methods
    public void addRequestInterceptor( RequestInterceptor ri ) {
	if( requestInt == null ) requestInt=new Vector();
	requestInt.addElement( ri );
	if( ri instanceof BaseInterceptor )
	    ((BaseInterceptor)ri).setDebug( debug );
    }
    public void addContextInterceptor( ContextInterceptor ci ) {
	if( contextInt == null ) contextInt=new Vector();
	contextInt.addElement( ci );
	if( ci instanceof BaseInterceptor )
	    ((BaseInterceptor)ci).setDebug( debug );
    }

    private void initContextManager() {
	if(requestInt==null)  initDefaultInterceptors();
	contextM=new ContextManager();
	contextM.setDebug( debug );
	
	for( int i=0; i< contextInt.size() ; i++ ) {
	    contextM.addContextInterceptor( (ContextInterceptor)
					    contextInt.elementAt( i ) );
	}

	for( int i=0; i< requestInt.size() ; i++ ) {
	    contextM.addRequestInterceptor( (RequestInterceptor)
					    requestInt.elementAt( i ) );
	}

	contextM.setWorkDir( workDir );

	try {
	    contextM.init();
	} catch( Exception ex ) {
	    ex.printStackTrace();
	}
	if(debug>0) log( "ContextManager initialized" );
    }
    
    private void initDefaultInterceptors() {
	// Explicitely set up all the interceptors we need.
	// The order is important ( like in apache hooks, it's a chain !)
	
	// no AutoSetup !
	
	// set workdir, engine header, auth Servlet, error servlet, loader
	WebXmlReader webXmlI=new WebXmlReader();
	webXmlI.setValidate( false );
	addContextInterceptor( webXmlI );

	PolicyInterceptor polI=new PolicyInterceptor();
	addContextInterceptor( polI );
	polI.setDebug(0);

	LoaderInterceptor loadI=new LoaderInterceptor();
	addContextInterceptor( loadI );

	DefaultCMSetter defaultCMI=new DefaultCMSetter();
	addContextInterceptor( defaultCMI );

	WorkDirInterceptor wdI=new WorkDirInterceptor();
	addContextInterceptor( wdI );

	
	LoadOnStartupInterceptor loadOnSI=new LoadOnStartupInterceptor();
	addContextInterceptor( loadOnSI );

	// Debug
	// 	LogEvents logEventsI=new LogEvents();
	// 	addRequestInterceptor( logEventsI );

	SessionInterceptor sessI=new SessionInterceptor();
	addRequestInterceptor( sessI );

	SimpleMapper1 mapI=new SimpleMapper1();
	addRequestInterceptor( mapI );
	mapI.setDebug(0);

	InvokerInterceptor invI=new InvokerInterceptor();
	addRequestInterceptor( invI );
	invI.setDebug(0);
	
	StaticInterceptor staticI=new StaticInterceptor();
	addRequestInterceptor( staticI );
	mapI.setDebug(0);

	addRequestInterceptor( new StandardSessionInterceptor());
	
	// access control ( find if a resource have constraints )
	AccessInterceptor accessI=new AccessInterceptor();
	addRequestInterceptor( accessI );
	accessI.setDebug(0);

	// set context class loader
	Jdk12Interceptor jdk12I=new Jdk12Interceptor();
	addRequestInterceptor( jdk12I );

	// xXXX
	//	addRequestInterceptor( new SimpleRealm());
    }
    

    // -------------------- Utils --------------------
    private void log( String s ) {
	System.out.println("WebAdapter: " + s );
    }

    /** Sample - you can use it to tomcat
     */
    public static void main( String args[] ) {
	try {
	    EmbededTomcat tc=new EmbededTomcat();
	    tc.setWorkDir( "/home/costin/src/jakarta/build/tomcat/work");
	    ServletContext sctx;
	    sctx=tc.addContext("", new URL
		( "file:/home/costin/src/jakarta/build/tomcat/webapps/ROOT"));
	    tc.initContext( sctx );
	    sctx=tc.addContext("/examples", new URL
		("file:/home/costin/src/jakarta/build/tomcat/webapps/examples"));
	    tc.initContext( sctx );
	    tc.addEndpoint( 8080, null, null);
	    tc.start();
	} catch (Throwable t ) {
	    t.printStackTrace();
	}
    }
	

}






