package net.atos.webtools.tapestry.core.models;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;

import net.atos.webtools.tapestry.core.TapestryCore;
import net.atos.webtools.tapestry.core.models.features.ComponentModel;
import net.atos.webtools.tapestry.core.models.features.MixinModel;
import net.atos.webtools.tapestry.core.models.features.PageModel;
import net.atos.webtools.tapestry.core.models.features.ServiceModel;
import net.atos.webtools.tapestry.core.util.Constants;
import net.atos.webtools.tapestry.core.util.ErrorMessages;
import net.atos.webtools.tapestry.core.util.JarClassLoader;
import net.atos.webtools.tapestry.core.util.Messages;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJarEntryResource;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.JarEntryDirectory;
import org.eclipse.jdt.internal.core.JarEntryFile;
import org.eclipse.jdt.internal.core.JarPackageFragmentRoot;
import org.eclipse.jdt.internal.core.JavaProject;
/**
 * This {@link Job} parses all the project to found Tapestry "features" 
 * (components, or pages, or mixins...) and to add them to the {@link ProjectModel}
 * 
 * For the moment, only components are loaded...
 * 
 * @author a160420
 *
 */
@SuppressWarnings("restriction")
public class FeatureFinder extends Job{
	/*
	 * Method names:
	 */
	private static final String GET_ROOT_PACKAGE = "getRootPackage";
	private static final String GET_PATH_PREFIX = "getPathPrefix";
	private static final String CONTRIBUTE_COMPONENT_CLASS_RESOLVER = "contributeComponentClassResolver";
	
	private ProjectModel projectModel;
	
	/**
	 * Initialize the {@link Job} with the {@link ProjectModel}
	 * 
	 * @param projectModel the projectModel that must have its {@link JavaProject} already set
	 */
	public FeatureFinder(ProjectModel projectModel) {
		super("Tapestry Finder for " + projectModel.getJavaProject().getProject().getName());
		this.projectModel = projectModel;
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		if (projectModel != null && projectModel.getJavaProject() != null) {
			
			//Loads PackageFragmentRoots (source folders, and all jars in classpath)
			IPackageFragmentRoot[] packageFragmentRoots;
			try {
				packageFragmentRoots = projectModel.getJavaProject().getAllPackageFragmentRoots();
			}
			catch (JavaModelException e) {
				TapestryCore.logError(ErrorMessages.PROJECT_CAN_T_BE_PARSED_FOR_PACKAGES, e);
				return new Status(IStatus.ERROR, TapestryCore.PLUGIN_ID, ErrorMessages.CAN_T_LOAD_THE_PACKAGES);
			}
			
			//Try to init a classloader with all classes, for reflexion (if it fails, we continue only looking in extension point)
			ClassLoader classLoader = null;
			try {
				classLoader = getCLassLoader(packageFragmentRoots);
			}
			catch (JavaModelException e) {
				TapestryCore.logWarning(ErrorMessages.CAN_T_SEARCH_DYNAMICALLY_FOR_TAPESTRY_LIBS, e);
			}
			
			for (IPackageFragmentRoot iPackageFragmentRoot : packageFragmentRoots) {
				if(isCancel(monitor)){
					return new Status(IStatus.CANCEL, TapestryCore.PLUGIN_ID, "Job Cancelled before " 
							+ iPackageFragmentRoot != null ? iPackageFragmentRoot.getElementName() : "");
				}
				
				/*
				 * Be careful, NOT to change order of step-1 & step-2:
				 *  JarPackageFragmentRoot is a subclass of PackageFragmentRoot, 
				 *  so jars would be managed in the wrong step 
				 */
				
				//---------- STEP-1: look for components in Classpath's Jars -------------- 
				if(iPackageFragmentRoot instanceof JarPackageFragmentRoot){
					//STEP-1-A: This is a Core Library (nothing special can be found in the Manifest)
					IPackageFragment corePackageFragment = iPackageFragmentRoot.getPackageFragment(Constants.TAPESTRY5_CORELIB_PACKAGE);
					if(corePackageFragment != null  && corePackageFragment.exists()){
						for (FeatureType featureType : FeatureType.values()) {
							loadFeaturesFromPackageFragmentRoot(iPackageFragmentRoot, "", Constants.TAPESTRY5_CORELIB_PACKAGE, featureType);
						}
					}
					//STEP-1-B: This is a custom Library ("tapestry-module-classes" property can be found in the path)
					else{
						Manifest manifest = getManifest(iPackageFragmentRoot);
						if(manifest != null){
							String appModule = manifest.getMainAttributes().getValue(Constants.TAPESTRY_MANIFEST_PROPERTY);
							if(appModule != null ){
								String[] prefixPackageStrings = getPackageInfoByReflection(classLoader, appModule);
								
								if(prefixPackageStrings != null && prefixPackageStrings.length>1 
										&& prefixPackageStrings[0] != null && prefixPackageStrings[1] != null){
									String prefix = prefixPackageStrings[0];
									String appPackageName = prefixPackageStrings[1];
									for (FeatureType featureType : FeatureType.values()) {
										loadFeaturesFromPackageFragmentRoot(iPackageFragmentRoot, prefix, appPackageName, featureType);
									}
								}
							}
						}
					}
				}
				
				//---------- STEP-2: look in sources for custom components ------------
				else {
					
					//STEP-2-A: sources from the project itself:
					if(iPackageFragmentRoot.getJavaProject() == projectModel.getJavaProject()){
						if(projectModel.getAppPackage() != null){
							for (FeatureType featureType : FeatureType.values()) {
								loadFeaturesFromPackageFragmentRoot(iPackageFragmentRoot, "", projectModel.getAppPackage(), featureType);
							}
						}
					}
					//STEP-2-B: Sources from a project in dependency: jar with components
					else {
						/*
						 * Usually, the manifest is found in only one of the PackageFragment (often src/main,/resources),
						 * but then, the sources are in the siblings PackageFragments (i.e. src/main/java)
						 */
						ProjectModel otherProjectModel = new ProjectModel(iPackageFragmentRoot.getJavaProject());
						otherProjectModel.init();
						if(otherProjectModel.getAppModule() != null){
							String[] prefixPackageStrings = getPackageInfoByReflection(classLoader, otherProjectModel.getAppModule());
							if(prefixPackageStrings != null && prefixPackageStrings.length>1 
									&& prefixPackageStrings[0] != null && prefixPackageStrings[1] != null){
								String prefix = prefixPackageStrings[0];
								String appPackageName = prefixPackageStrings[1];
								for (FeatureType featureType : FeatureType.values()) {
									loadFeaturesFromPackageFragmentRoot(iPackageFragmentRoot, prefix, appPackageName, featureType);
								}
							}
						}
					}
				}
			}
		}
		return new Status(IStatus.OK, TapestryCore.PLUGIN_ID, Messages.JOB_DONE);
	}
	
	/**
	 * 
	 * 
	 * @param packageFragmentRoot
	 * @param prefix
	 * @param appPackageName
	 * @param featureType 
	 */
	private void loadFeaturesFromPackageFragmentRoot(IPackageFragmentRoot packageFragmentRoot, String prefix, String appPackageName, FeatureType featureType){
		if(packageFragmentRoot != null && packageFragmentRoot.exists() && appPackageName != null){
			IJavaElement[] children;
			try {
				children = packageFragmentRoot.getChildren();
			}
			catch (JavaModelException e) {
				TapestryCore.logWarning(ErrorMessages.CAN_T_LOAD_TAPESTRY_LIBS_FROM + packageFragmentRoot.getElementName() 
						+ " : " + packageFragmentRoot.getJavaProject().getElementName(), e);
				return;
			}
			String  featuresPackageName = appPackageName + "." + featureType.getSubPackage();
			
			for (IJavaElement iJavaElement : children) {
				if(iJavaElement instanceof IPackageFragment){
					IPackageFragment classesPackage = (IPackageFragment) iJavaElement;
					if(classesPackage.getElementName().startsWith(featuresPackageName)){
						String subPackage;
						if(classesPackage.getElementName().length() > featuresPackageName.length()){
							subPackage = classesPackage.getElementName().substring(featuresPackageName.length() + 1);
						}
						else{
							subPackage = "";
						}
						
						loadFeaturesFromPackageFragment(featureType, prefix, classesPackage, subPackage);
					}
				}
			}
		}
	}
	
	/**
	 * Enriches the projectModel with classes from the package...
	 * @param subPackage 
	 * 
	 * @param featureType: the type of features to import (page, component, mixin...) 
	 * @param prefix: the Tapestry prefix for the component lib
	 * @param componentsPackage: the package to explore
	 * @param subPackage: the sub-package inside "components", if any, or empty String
	 */
	private void loadFeaturesFromPackageFragment(FeatureType featureType, String prefix, IPackageFragment classesPackage, String subPackage) {
		if(classesPackage != null && classesPackage.exists()){
			String source = classesPackage.getPath().toPortableString();
			try {
				ITypeRoot[] classFiles = classesPackage.getClassFiles();
				for (ITypeRoot classFile : classFiles) {
					if(classFile.exists() && ! classFile.getElementName().contains("$")){
						loadFeatureFromClass(featureType, classFile, prefix, source, subPackage);
					}
				}
			}
			catch (JavaModelException jme) {
				TapestryCore.logError(ErrorMessages.CAN_T_FIND_CLASSES_IN_PACKAGE + classesPackage.getElementName(), jme);
			}
			
			try {
				ICompilationUnit[] compilationUnits = classesPackage.getCompilationUnits();
				for (ICompilationUnit compilationUnit : compilationUnits) {
					if(compilationUnit.exists() && ! compilationUnit.getElementName().contains("$")){
						loadFeatureFromClass(featureType, compilationUnit, prefix, source, subPackage);
					}
				}
			}
			catch (JavaModelException jme) {
				TapestryCore.logError(ErrorMessages.CAN_T_FIND_COMPILATION_UNITS_IN_PACKAGE + classesPackage.getElementName(), jme);
			}
		}
	}
	
	/**
	 * Instantiate the right component (corresponding to featureType), and add it to the project model
	 * 
	 * @param featureType
	 * @param typeRoot
	 * @param prefix
	 * @param source
	 * @param subPackage
	 */
	private void loadFeatureFromClass(FeatureType featureType, ITypeRoot typeRoot, String prefix, String source, String subPackage){
		if(projectModel != null && typeRoot != null){
			IType type = typeRoot.findPrimaryType();
			if(type != null){
				if(featureType == FeatureType.COMPONENT){
					projectModel.addComponent(new ComponentModel(prefix, type, projectModel, source, subPackage));
				}
				else if(featureType == FeatureType.MIXIN){
					projectModel.addMixin(new MixinModel(prefix, type, projectModel, source, subPackage));
				}
				else if(featureType == FeatureType.PAGE){
					projectModel.addPage(new PageModel(prefix, type, projectModel, source, subPackage));
				}
				else if(featureType == FeatureType.SERVICE){
					projectModel.addService(new ServiceModel(prefix, type, projectModel, source, subPackage));
				}
			}
		}
	}

	/**
	 * Loads the appModule class by reflection, and tries to execute a method with that signature:
	 * <p>
	 *   <code>public static void contributeComponentClassResolver(Configuration<LibraryMapping> configuration)</code>
	 * </p>
	 * @param classLoader the classloader that should have already registered all the classes
	 * @param appModule the fully qualified paramName of the AppModule class
	 * @return
	 */
	private String[] getPackageInfoByReflection(ClassLoader classLoader, String appModule){
		String[] prefixPackageStrings = new String[2];
		
		if(classLoader == null){
			return getPackageInfoByExtensionPoint(appModule);
		}
		
		try {
			Class<?> appModuleClass = classLoader.loadClass(appModule);
			Class<?> configurationClass = classLoader.loadClass(Constants.TAPESTRY5_IOC_CONFIGURATION);
			Class<?> validatingConfigurationWrapperClass = classLoader.loadClass(Constants.TAPESTRY5_IOC_CONFIGURATION_WRAPPER);
			Class<?> libraryMappingClass = classLoader.loadClass(Constants.TAPESTRY5_SERVICES_LIBRARY_MAPPING);
			
			@SuppressWarnings("rawtypes")
			ArrayList arrayList = new ArrayList();
			
			//We know this is the only constructor, but this is dangerous...
			Constructor<?> constructor = validatingConfigurationWrapperClass.getConstructors()[0];
			
			Object validatingConfigurationWrapperInstance;
			
			Type[] typeParameters = constructor.getGenericParameterTypes();
			if(typeParameters != null && typeParameters.length == 4 && (typeParameters[1] instanceof Class) && ((Class<?>) typeParameters[1]).equals(String.class)){
				//Tapestry 5.1
				//"public ValidatingConfigurationWrapper(Collection<T> collection, String serviceId, Class expectedType, ObjectLocator locator)"
				validatingConfigurationWrapperInstance = constructor.newInstance(arrayList,"serviceId", libraryMappingClass, null);
			}
			else if(typeParameters != null && typeParameters.length == 4){
				//Tapestry 5.2 (the order of the params have changed in the constructor !!!)
				//"public ValidatingConfigurationWrapper(Class<T> expectedType, ObjectLocator locator, Collection<T> collection, String serviceId)"
				validatingConfigurationWrapperInstance = constructor.newInstance(libraryMappingClass, null, arrayList, "serviceId");
			}
			else if(typeParameters != null && typeParameters.length == 5){
				Class<?> typeCoercerProxyImplClass = classLoader.loadClass("org.apache.tapestry5.ioc.internal.TypeCoercerProxyImpl");
				Constructor<?> typeCoercerProxyImplConstructor = typeCoercerProxyImplClass.getConstructors()[0];
				Object typeCoercer = typeCoercerProxyImplConstructor.newInstance((Object)null);
				//Tapestry 5.3 (one new parameter for ValidatingConfigurationWrapper)
				validatingConfigurationWrapperInstance = constructor.newInstance(libraryMappingClass, null, typeCoercer, arrayList, "serviceId");
			}
			else{
//				Activator.logWarning("Reflection failed for " + appModule + " -> No component loaded.");
				return getPackageInfoByExtensionPoint(appModule);
			}
			
			try{
				//CASE-1:
				//"public static void contributeComponentClassResolver(Configuration<LibraryMapping> configuration)"
				
				//Method is static -> calling object is null
				appModuleClass.getMethod(CONTRIBUTE_COMPONENT_CLASS_RESOLVER, configurationClass).invoke(null, validatingConfigurationWrapperInstance);
			}
			catch (NoSuchMethodException e) {
				//CASE-2:
				//public static void contributeComponentClassResolver(Configuration<LibraryMapping> configuration, Logger log)
				
				Class<?> loggerClass = classLoader.loadClass(Constants.SLF4J_LOGGER);
				try{
					appModuleClass.getMethod(CONTRIBUTE_COMPONENT_CLASS_RESOLVER, configurationClass, loggerClass)
							.invoke(null, validatingConfigurationWrapperInstance, null);
				}
				catch(InvocationTargetException npe){
					//Logger is null, so there's a NPE launching this exception, 
					//but we don't care because it's launched after the values are set...
					//yeah it's not the most beautiful part of the code!
				}
			}
			
			for (Object libraryMapping : arrayList) {
				//Call LibraryMapping.getPathPrefix()
				Object pathPrefix = libraryMappingClass.getMethod(GET_PATH_PREFIX, new Class[0]).invoke(libraryMapping, new Object[0]);
				prefixPackageStrings[0] = (String) pathPrefix;
				
				//Call LibraryMapping.getRootPackage()
				Object rootPackage = libraryMappingClass.getMethod(GET_ROOT_PACKAGE, new Class[0]).invoke(libraryMapping, new Object[0]);
				prefixPackageStrings[1] = (String) rootPackage;
			}
			
			return prefixPackageStrings;
		}
		catch (NoSuchMethodException e) {
//			Activator.logInfo("Reflection failed for " + appModule + " -> No component loaded.\n" + e.getMessage());
			return getPackageInfoByExtensionPoint(appModule);
		}
		catch (ClassNotFoundException e) {
//			Activator.logError("Reflection failed for " + appModule + " -> No component loaded.\n" + e.getMessage());
			return getPackageInfoByExtensionPoint(appModule);
		}
		catch (IllegalArgumentException e) {
//			Activator.logError("Reflection failed for " + appModule + " -> No component loaded.\n" + e.getMessage());
			return getPackageInfoByExtensionPoint(appModule);
		}
		catch (Exception e) {
//			Activator.logWarning("Reflection failed for " + appModule + " -> No component loaded.", e);
			return getPackageInfoByExtensionPoint(appModule);
		}
		catch(LinkageError e){
//			Activator.logWarning("Reflection failed for " + appModule + " -> No component loaded.", e);
			return getPackageInfoByExtensionPoint(appModule);
		}
	}
	
	/**
	 * Given the appModule, its searches for "prefix" and "package" information in the custom
	 * extension point (net.atos.webtools.tapestry.components.libs). 
	 * 
	 * @param appModule: the fully qualified name of the appModule
	 * @return String[]{prefix, package}
	 */
	private String[] getPackageInfoByExtensionPoint(String appModule){
		String[] prefixPackageStrings = new String[2];
		if(appModule != null && appModule.length()>0){
			IExtensionPoint extensionPoint = Platform.getExtensionRegistry().getExtensionPoint(TapestryCore.PLUGIN_ID,
					Constants.COMPONENTS_LIBS_EXT);
			// Read Profiles & extensions separately
			if(extensionPoint != null){
				for (IExtension extension : extensionPoint.getExtensions()) {
					for (IConfigurationElement configurationElement : extension.getConfigurationElements()) {
						if (appModule.equals(configurationElement.getAttribute("appModule"))){
							prefixPackageStrings[0] = configurationElement.getAttribute("prefix");
							prefixPackageStrings[1] = configurationElement.getAttribute("package");
							//Activator.logInfo("... found in the extension points - " + appModule);
						}
					}
				}
			}
			if(prefixPackageStrings[0] == null || prefixPackageStrings[1] == null){
				return getPackageInfoByDeduction(appModule);
			}
		}
		return prefixPackageStrings;
	}
	
	/**
	 * Third possibility, WITH SOME DRAWBACKS:
	 * We suppose that AppModule class is at the root level of ".services" package, so the appModule is the parent one
	 * But for the prefix there's no way to guess, so we return the empty String.
	 * 
	 * @param appModule
	 * @return
	 */
	private String[] getPackageInfoByDeduction(String appModule){
		String[] prefixPackageStrings = new String[2];
		if(appModule.contains(".services")){
			prefixPackageStrings[0] = "?";
			prefixPackageStrings[1] = appModule.substring(0, appModule.lastIndexOf(".services"));
			TapestryCore.logWarning("Components/Mixins from " + appModule
					+ " will not have their prefix - please report to support");
		}
		
		return prefixPackageStrings;
	}

	/**
	 * Creates a classloader with all the jars in the classpath and all the compiled files
	 * in the outputdir
	 * @param packageFragmentRoots2 
	 * 
	 * @return the ClassLoader
	 * @throws JavaModelException
	 */
	private ClassLoader getCLassLoader(IPackageFragmentRoot[] packageFragmentRoots) throws JavaModelException {
		//STEP-1: jarclassloader with all the jars (and the plugin one as parent)
		
		List<File> theJarFiles = new ArrayList<File>(packageFragmentRoots.length);
		
		for (IPackageFragmentRoot iPackageFragmentRoot : packageFragmentRoots) {
			if(iPackageFragmentRoot instanceof JarPackageFragmentRoot){
				theJarFiles.add(new File(iPackageFragmentRoot.getPath().toString()));
			}
		}
		
		JarClassLoader jarClassLoader = new JarClassLoader(theJarFiles, this.getClass().getClassLoader());
	
		
		//STEP-2: URL classloader with all the output dirs (and the previous one as parent)
		Set<IJavaProject> javaProjects = new HashSet<IJavaProject>();
		for (IPackageFragmentRoot iPackageFragmentRoot : packageFragmentRoots) {
			javaProjects.add(iPackageFragmentRoot.getJavaProject());
		}
		Set<URL> URLs = new HashSet<URL>();
		for (IJavaProject iJavaProject : javaProjects) {
			
			URI workspaceURI = iJavaProject.getProject().getWorkspace().getRoot().getLocationURI();
			IPath outputLocation = iJavaProject.getOutputLocation();
			try {
				URLs.add(new URL(workspaceURI.toURL().toString() + outputLocation + "/"));
			}
			catch (MalformedURLException e) {
				//log the issue, but try to continue on other outputdirs
				TapestryCore.logWarning("Can't add URL for " + outputLocation);
			}
			
			
			
			
			IClasspathEntry[] rawClasspath = iJavaProject.getRawClasspath();
			for (IClasspathEntry iClasspathEntry : rawClasspath) {
				IPath outputLocation2 = iClasspathEntry.getOutputLocation();
				if(outputLocation2 != null){
					try{
						URLs.add(new URL(workspaceURI.toURL().toString() + outputLocation2 + "/"));
					}
					catch (MalformedURLException e) {
						//log the issue, but try to continue on other outputdirs
						TapestryCore.logWarning("Can't add URL for " + outputLocation2);
					}
				}
			}
		}
		
		return new URLClassLoader(URLs.toArray(new URL[URLs.size()]), jarClassLoader, null);
	}

	/**
	 * Get the manifest information for a given {@link IPackageFragmentRoot}
	 * It looks in the
	 * 
	 * @param packageFragmentRoot the IPackageFragmentRoot to scan
	 * @return the manifest or null if it's not found
	 */
	protected static Manifest getManifest(IPackageFragmentRoot packageFragmentRoot){
		try {
			if(packageFragmentRoot.exists()){
				Object[] nonJavaResources = packageFragmentRoot.getNonJavaResources();
				
				for (Object object : nonJavaResources) {
					if(object instanceof JarEntryDirectory){
						JarEntryDirectory jarDir = (JarEntryDirectory) object;
						if(jarDir.getName().equals(Constants.META_INF)){
							for (IJarEntryResource inMetaInfFile : jarDir.getChildren()) {
								if(inMetaInfFile instanceof JarEntryFile && inMetaInfFile.getName().equals(Constants.MANIFEST_MF)){
									InputStream contents = inMetaInfFile.getContents();
									Manifest manifest = new Manifest(contents);
									contents.close();
									return manifest;
								}
							}
						}
					}
					else if(object instanceof IFolder){
						IFolder folder = (IFolder) object;
						IFile file = folder.getFile(Constants.MANIFEST_MF);
						if(file != null && file.exists()){
							file.refreshLocal(IResource.DEPTH_ZERO, null);
							InputStream contents = file.getContents();
							Manifest manifest = new Manifest(contents);
							contents.close();
							return manifest;
						}
					}
				}
			}
		}
		catch (Exception e) {
			TapestryCore.logError(ErrorMessages.CAN_T_LOAD_THE_MANIFEST_FOR_PACKAGE + packageFragmentRoot, e);
		}
		
		return null;
	}
	
	
	private boolean isCancel(IProgressMonitor monitor) {
		if(monitor.isCanceled()){
			if(projectModel != null && projectModel.getJavaProject() != null){
				TapestryCore.logWarning("Loading of Project Model has been cancelled for " + projectModel.getJavaProject().getElementName());
			}
			else{
				TapestryCore.logWarning("Loading of Project Model has been cancelled");
			}
			return true;
		}
		return false;
	}
}