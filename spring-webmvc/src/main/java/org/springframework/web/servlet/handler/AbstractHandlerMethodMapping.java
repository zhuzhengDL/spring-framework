/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.handler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.MethodIntrospector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Abstract base class for {@link HandlerMapping} implementations that define
 * a mapping between a request and a {@link HandlerMethod}.
 *
 * <p>For each registered handler method, a unique mapping is maintained with
 * subclasses defining the details of the mapping type {@code <T>}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @param <T> the mapping for a {@link HandlerMethod} containing the conditions
 * needed to match the handler method to an incoming request.
 */
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {

	/**
	 * Bean name prefix for target beans behind scoped proxies. Used to exclude those
	 * targets from handler method detection, in favor of the corresponding proxies.
	 * <p>We're not checking the autowire-candidate status here, which is how the
	 * proxy target filtering problem is being handled at the autowiring level,
	 * since autowire-candidate may have been turned to {@code false} for other
	 * reasons, while still expecting the bean to be eligible for handler methods.
	 * <p>Originally defined in {@link org.springframework.aop.scope.ScopedProxyUtils}
	 * but duplicated here to avoid a hard dependency on the spring-aop module.
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

	private static final HandlerMethod PREFLIGHT_AMBIGUOUS_MATCH =
			new HandlerMethod(new EmptyHandler(), ClassUtils.getMethod(EmptyHandler.class, "handle"));

	private static final CorsConfiguration ALLOW_CORS_CONFIG = new CorsConfiguration();

	static {
		ALLOW_CORS_CONFIG.addAllowedOriginPattern("*");
		ALLOW_CORS_CONFIG.addAllowedMethod("*");
		ALLOW_CORS_CONFIG.addAllowedHeader("*");
		ALLOW_CORS_CONFIG.setAllowCredentials(true);
	}

	/**
	 * 是否只扫描当前context里面可访问的 HandlerMethod
	 * false表示不会查父类的context
	 */
	private boolean detectHandlerMethodsInAncestorContexts = false;

	/**
	 *  Mapping 命名策略
	 */
	@Nullable
	private HandlerMethodMappingNamingStrategy<T> namingStrategy;

	/**
	 *  Mapping 注册表
	 */
	private final MappingRegistry mappingRegistry = new MappingRegistry();


	@Override
	public void setPatternParser(PathPatternParser patternParser) {
		Assert.state(this.mappingRegistry.getRegistrations().isEmpty(),
				"PathPatternParser must be set before the initialization of " +
						"request mappings through InitializingBean#afterPropertiesSet.");
		super.setPatternParser(patternParser);
	}

	/**
	 * Whether to detect handler methods in beans in ancestor ApplicationContexts.
	 * <p>Default is "false": Only beans in the current ApplicationContext are
	 * considered, i.e. only in the context that this HandlerMapping itself
	 * is defined in (typically the current DispatcherServlet's context).
	 * <p>Switch this flag on to detect handler beans in ancestor contexts
	 * (typically the Spring root WebApplicationContext) as well.
	 * @see #getCandidateBeanNames()
	 */
	public void setDetectHandlerMethodsInAncestorContexts(boolean detectHandlerMethodsInAncestorContexts) {
		this.detectHandlerMethodsInAncestorContexts = detectHandlerMethodsInAncestorContexts;
	}

	/**
	 * Configure the naming strategy to use for assigning a default name to every
	 * mapped handler method.
	 * <p>The default naming strategy is based on the capital letters of the
	 * class name followed by "#" and then the method name, e.g. "TC#getFoo"
	 * for a class named TestController with method getFoo.
	 */
	public void setHandlerMethodMappingNamingStrategy(HandlerMethodMappingNamingStrategy<T> namingStrategy) {
		this.namingStrategy = namingStrategy;
	}

	/**
	 * Return the configured naming strategy or {@code null}.
	 */
	@Nullable
	public HandlerMethodMappingNamingStrategy<T> getNamingStrategy() {
		return this.namingStrategy;
	}

	/**
	 * Return a (read-only) map with all mappings and HandlerMethod's.
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
		this.mappingRegistry.acquireReadLock();
		try {
			return Collections.unmodifiableMap(
					this.mappingRegistry.getRegistrations().entrySet().stream()
							.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().handlerMethod)));
		}
		finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Return the handler methods for the given mapping name.
	 * @param mappingName the mapping name
	 * @return a list of matching HandlerMethod's or {@code null}; the returned
	 * list will never be modified and is safe to iterate.
	 * @see #setHandlerMethodMappingNamingStrategy
	 */
	@Nullable
	public List<HandlerMethod> getHandlerMethodsForMappingName(String mappingName) {
		return this.mappingRegistry.getHandlerMethodsByMappingName(mappingName);
	}

	/**
	 * Return the internal mapping registry. Provided for testing purposes.
	 */
	MappingRegistry getMappingRegistry() {
		return this.mappingRegistry;
	}

	/**
	 * Register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping for the handler method
	 * @param handler the handler
	 * @param method the method
	 */
	public void registerMapping(T mapping, Object handler, Method method) {
		if (logger.isTraceEnabled()) {
			logger.trace("Register \"" + mapping + "\" to " + method.toGenericString());
		}
		this.mappingRegistry.register(mapping, handler, method);
	}

	/** 取消注册给定的映射。
	 * Un-register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping to unregister
	 */
	public void unregisterMapping(T mapping) {
		if (logger.isTraceEnabled()) {
			logger.trace("Unregister mapping \"" + mapping + "\"");
		}
		this.mappingRegistry.unregister(mapping);
	}


	// Handler method detection

	/** 在初始化时检测 Handlermethod。
	 *
	 * Detects handler methods at initialization.
	 * @see #initHandlerMethods
	 */
	@Override
	public void afterPropertiesSet() {
		// <x> 初始化处理器的方法们
		initHandlerMethods();
	}

	/** 扫描 ApplicationContext 中的 bean，检测和注册处理程序方法。
	 *
	 * Scan beans in the ApplicationContext, detect and register handler methods.
	 * @see #getCandidateBeanNames()
	 * @see #processCandidateBean
	 * @see #handlerMethodsInitialized
	 */
	protected void initHandlerMethods() {
		// <1.1> 遍历 容器内的所有Bean ，逐个处理
		for (String beanName : getCandidateBeanNames()) {
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
				// <1.2> 处理 Bean
				processCandidateBean(beanName);
			}
		}
		// <2> 初始化处理器的方法们。目前是空方法，暂无具体的实现
		handlerMethodsInitialized(getHandlerMethods());
	}

	/** 确定应用程序上下文中候选 bean 的名称。
	 * Determine the names of candidate beans in the application context.
	 * @since 5.1
	 * @see #setDetectHandlerMethodsInAncestorContexts
	 * @see BeanFactoryUtils#beanNamesForTypeIncludingAncestors
	 */
	protected String[] getCandidateBeanNames() {
		return (this.detectHandlerMethodsInAncestorContexts ?
				//获取包含父类Context里面注册的所有bean
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(obtainApplicationContext(), Object.class) :
				//获取当前context里面注册的所有bean
				obtainApplicationContext().getBeanNamesForType(Object.class));
	}

	/**
	 * Determine the type of the specified candidate bean and call
	 * {@link #detectHandlerMethods} if identified as a handler type.
	 * <p>This implementation avoids bean creation through checking
	 * {@link org.springframework.beans.factory.BeanFactory#getType}
	 * and calling {@link #detectHandlerMethods} with the bean name.
	 * @param beanName the name of the candidate bean
	 * @since 5.1
	 * @see #isHandler
	 * @see #detectHandlerMethods
	 */
	protected void processCandidateBean(String beanName) {
		Class<?> beanType = null;
		try {
			// <1> 获得 Bean 对应的类型
			beanType = obtainApplicationContext().getType(beanName);
		}
		catch (Throwable ex) {
			// An unresolvable bean type, probably from a lazy bean - let's ignore it.
			if (logger.isTraceEnabled()) {
				logger.trace("Could not resolve type for bean '" + beanName + "'", ex);
			}
		}
		// 判断 Bean 是否为处理器，如果是(类是否有 Controller或者 RequestMapping注解)，则扫描处理器方法
		//
		if (beanType != null && isHandler(beanType)) {
			detectHandlerMethods(beanName);
		}
	}

	/** 在指定的处理程序 bean 中查找HandlerMethods方法。
	 * Look for handler methods in the specified handler bean.
	 * @param handler either a bean name or an actual handler instance
	 * @see #getMappingForMethod
	 */
	protected void detectHandlerMethods(Object handler) {
		// <1> 获得处理器类型
		Class<?> handlerType = (handler instanceof String ?
				obtainApplicationContext().getType((String) handler) : handler.getClass());

		if (handlerType != null) {
			// <2> 获得真实的类。因为，handlerType 可能是代理类
			Class<?> userType = ClassUtils.getUserClass(handlerType);
			// <3> 获得匹配的方法的集合,T 为通过getMappingForMethod返回的RequestMappingInfo
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
					(MethodIntrospector.MetadataLookup<T>) method -> {
						try {
							//根据method上面的注解@RequestMapping 生成对应的 RequestMappingInfo
							return getMappingForMethod(method, userType);
						}
						catch (Throwable ex) {
							throw new IllegalStateException("Invalid mapping on handler class [" +
									userType.getName() + "]: " + method, ex);
						}
					});
			//日志打印
			if (logger.isTraceEnabled()) {
				logger.trace(formatMappings(userType, methods));
			}
			else if (mappingsLogger.isDebugEnabled()) {
				mappingsLogger.debug(formatMappings(userType, methods));
			}
			// <4> 遍历方法，逐个注册 HandlerMethod
			methods.forEach((method, mapping) -> {
				Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
				registerHandlerMethod(handler, invocableMethod, mapping);
			});
		}
	}

	private String formatMappings(Class<?> userType, Map<Method, T> methods) {
		String formattedType = Arrays.stream(ClassUtils.getPackageName(userType).split("\\."))
				.map(p -> p.substring(0, 1))
				.collect(Collectors.joining(".", "", "." + userType.getSimpleName()));
		Function<Method, String> methodFormatter = method -> Arrays.stream(method.getParameterTypes())
				.map(Class::getSimpleName)
				.collect(Collectors.joining(",", "(", ")"));
		return methods.entrySet().stream()
				.map(e -> {
					Method method = e.getKey();
					return e.getValue() + ": " + method.getName() + methodFormatter.apply(method);
				})
				.collect(Collectors.joining("\n\t", "\n\t" + formattedType + ":" + "\n\t", ""));
	}

	/**  注册 HandlerMethod
	 * 注册处理程序方法及其唯一映射。在启动时为每个检测到的处理程序方法调用。
	 *
	 * Register a handler method and its unique mapping. Invoked at startup for
	 * each detected handler method.
	 * @param handler the bean name of the handler or the handler instance
	 * @param method the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 * @throws IllegalStateException if another method was already registered
	 * under the same mapping
	 */
	protected void registerHandlerMethod(Object handler, Method method, T mapping) {
		this.mappingRegistry.register(mapping, handler, method);
	}

	/** 创建 HandlerMethod 实例。
	 * Create the HandlerMethod instance.
	 * @param handler either a bean name or an actual handler instance
	 * @param method the target method
	 * @return the created HandlerMethod
	 */
	protected HandlerMethod createHandlerMethod(Object handler, Method method) {
		// <1> 如果 handler 类型为 String， 说明对应一个 Bean 对象，例如 UserController
		// 使用 @Controller 注解后，默认 handler 为它的 beanName ，即 `userController`
		//一般是这种情况，扫描到的容器中的bean name
		if (handler instanceof String) {
			return new HandlerMethod((String) handler,
					obtainApplicationContext().getAutowireCapableBeanFactory(), method);

		}
		// <2> 如果 handler 类型非 String ，说明是一个已经是一个 handler 对象，就无需处理，直接创建 HandlerMethod 对象
		return new HandlerMethod(handler, method);
	}

	/** 提取并返回映射的 CORS 配置。
	 * Extract and return the CORS configuration for the mapping.
	 */
	@Nullable
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, T mapping) {
		return null;
	}

	/** 在检测到所有HandlerMethod方法后调用。
	 * Invoked after all handler methods have been detected.
	 * @param handlerMethods a read-only map with handler methods and mappings.
	 */
	protected void handlerMethodsInitialized(Map<T, HandlerMethod> handlerMethods) {
		// Total includes detected mappings + explicit registrations via registerMapping
		int total = handlerMethods.size();
		if ((logger.isTraceEnabled() && total == 0) || (logger.isDebugEnabled() && total > 0) ) {
			logger.debug(total + " mappings in " + formatMappingName());
		}
	}


	// Handler method lookup

	/** 查找给定请求的处理程序方法。
	 *
	 * Look up a handler method for the given request.
	 */
	@Override
	@Nullable
	protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
		// <1> 获得请求的路径
		String lookupPath = initLookupPath(request);
		// <2> 获得写锁
		this.mappingRegistry.acquireReadLock();
		try {
			// <3> 获得 HandlerMethod 对象
			HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
			// <4> 进一步，获得 HandlerMethod 对象（如果提供的实例包含一个 bean 名称而不是一个对象实例，需要获取工厂里面的对应bean昨晚hander）
			return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null);
		}
		finally {
			// <5> 释放写锁
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**查找当前请求的最佳匹配处理Controller的方法。
	 * 如果找到多个匹配项，则选择最佳匹配项。
	 *
	 * Look up the best-matching handler method for the current request.
	 * If multiple matches are found, the best match is selected.
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @return the best-matching handler method, or {@code null} if no match
	 * @see #handleMatch(Object, String, HttpServletRequest)
	 * @see #handleNoMatch(Set, String, HttpServletRequest)
	 */
	@Nullable
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {
		// <1> Match 数组，存储匹配上当前请求的结果
		List<Match> matches = new ArrayList<>();
		// <1.1> 优先，基于直接 URL 的 Mapping 们，进行匹配
		List<T> directPathMatches = this.mappingRegistry.getMappingsByDirectPath(lookupPath);
		if (directPathMatches != null) {
			addMatchingMappings(directPathMatches, matches, request);
		}
		// <1.2> 其次，扫描注册表的 Mapping 们，进行匹配
		if (matches.isEmpty()) {
			addMatchingMappings(this.mappingRegistry.getRegistrations().keySet(), matches, request);
		}
		// <2> 如果匹配到，则获取最佳匹配的 Match 对象的 handlerMethod 属性
		if (!matches.isEmpty()) {
			// <2.2> 获得首个 Match 对象
			Match bestMatch = matches.get(0);
			// <2.3> 处理存在多个 Match 对象的情况！！
			if (matches.size() > 1) {
				// <2.1> 创建 MatchComparator 对象，排序 matches 结果
				Comparator<Match> comparator = new MatchComparator(getMappingComparator(request));
				matches.sort(comparator);
				//获取排序后的第一个作为最佳匹配项
				bestMatch = matches.get(0);
				if (logger.isTraceEnabled()) {
					logger.trace(matches.size() + " matching mappings: " + matches);
				}
				// cors处理
				if (CorsUtils.isPreFlightRequest(request)) {
					for (Match match : matches) {
						if (match.hasCorsConfig()) {
							return PREFLIGHT_AMBIGUOUS_MATCH;
						}
					}
				}
				else {
					// 比较 bestMatch 和 secondBestMatch ，如果相等，说明有问题，抛出 IllegalStateException 异常
					// 因为，两个优先级一样高，说明无法判断谁更优先
					Match secondBestMatch = matches.get(1);
					if (comparator.compare(bestMatch, secondBestMatch) == 0) {
						Method m1 = bestMatch.getHandlerMethod().getMethod();
						Method m2 = secondBestMatch.getHandlerMethod().getMethod();
						String uri = request.getRequestURI();
						throw new IllegalStateException(
								"Ambiguous handler methods mapped for '" + uri + "': {" + m1 + ", " + m2 + "}");
					}
				}
			}
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, bestMatch.getHandlerMethod());
			// <2.4> 处理首个 Match 对象--存入当前request属性中
			handleMatch(bestMatch.mapping, lookupPath, request);
			// <2.5> 返回首个 Match 对象的 handlerMethod 属性
			return bestMatch.getHandlerMethod();
		}
		else {
			// <3> 如果匹配不到，则处理不匹配的情况
			return handleNoMatch(this.mappingRegistry.getRegistrations().keySet(), lookupPath, request);
		}
	}

	private void addMatchingMappings(Collection<T> mappings, List<Match> matches, HttpServletRequest request) {
		// 遍历 Mapping（RequestMappingInfo） 数组
		for (T mapping : mappings) {
			// <1> 执行匹配 子类去实现
			T match = getMatchingMapping(mapping, request);
			if (match != null) {
				// <2> 如果匹配，则创建 Match 对象，添加到 matches 中
				matches.add(new Match(match, this.mappingRegistry.getRegistrations().get(mapping)));
			}
		}
	}

	/** 在找到匹配的映射时调用。
	 * Invoked when a matching mapping is found.
	 * @param mapping the matching mapping
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 */
	protected void handleMatch(T mapping, String lookupPath, HttpServletRequest request) {
		request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
	}

	/** 在未找到匹配映射时调用。
	 * Invoked when no matching mapping is not found.
	 * @param mappings all registered mappings
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @throws ServletException in case of errors
	 */
	@Nullable
	protected HandlerMethod handleNoMatch(Set<T> mappings, String lookupPath, HttpServletRequest request)
			throws Exception {

		return null;
	}

	@Override
	protected boolean hasCorsConfigurationSource(Object handler) {
		return super.hasCorsConfigurationSource(handler) ||
				(handler instanceof HandlerMethod &&
						this.mappingRegistry.getCorsConfiguration((HandlerMethod) handler) != null);
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, HttpServletRequest request) {
		CorsConfiguration corsConfig = super.getCorsConfiguration(handler, request);
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			if (handlerMethod.equals(PREFLIGHT_AMBIGUOUS_MATCH)) {
				return AbstractHandlerMethodMapping.ALLOW_CORS_CONFIG;
			}
			else {
				CorsConfiguration corsConfigFromMethod = this.mappingRegistry.getCorsConfiguration(handlerMethod);
				corsConfig = (corsConfig != null ? corsConfig.combine(corsConfigFromMethod) : corsConfigFromMethod);
			}
		}
		return corsConfig;
	}


	// Abstract template methods

	/** 给定类型是否是具有handler methods方法的处理程序。
	 * Whether the given type is a handler with handler methods.
	 * @param beanType the type of the bean being checked
	 * @return "true" if this a handler type, "false" otherwise.
	 */
	protected abstract boolean isHandler(Class<?> beanType);

	/** 为处理程序方法提供映射。
	 * 不能为其提供映射的方法不是处理程序方法。
	 *
	 * Provide the mapping for a handler method. A method for which no
	 * mapping can be provided is not a handler method.
	 * @param method the method to provide a mapping for
	 * @param handlerType the handler type, possibly a sub-type of the method's
	 * declaring class
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * Extract and return the URL paths contained in the supplied mapping.
	 * @deprecated as of 5.3 in favor of providing non-pattern mappings via
	 * {@link #getDirectPaths(Object)} instead
	 */
	@Deprecated
	protected Set<String> getMappingPathPatterns(T mapping) {
		return Collections.emptySet();
	}

	/** 返回请求映射的直接路径。
	 * Return the request mapping paths that are not patterns.
	 * @since 5.3
	 */
	protected Set<String> getDirectPaths(T mapping) {
		Set<String> urls = Collections.emptySet();
		// 遍历 Mapping 对应的原始路径
		for (String path : getMappingPathPatterns(mapping)) {
			// 非**模式**路径
			if (!getPathMatcher().isPattern(path)) {
				urls = (urls.isEmpty() ? new HashSet<>(1) : urls);
				urls.add(path);
			}
		}
		return urls;
	}

	/** 检查映射是否与当前请求匹配，并返回一个（可能是新的）映射与当前请求相关的条件。
	 *
	 * Check if a mapping matches the current request and return a (potentially
	 * new) mapping with conditions relevant to the current request.
	 * @param mapping the mapping to get a match for
	 * @param request the current HTTP servlet request
	 * @return the match, or {@code null} if the mapping doesn't match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, HttpServletRequest request);

	/**
	 * Return a comparator for sorting matching mappings.
	 * The returned comparator should sort 'better' matches higher.
	 * @param request the current request
	 * @return the comparator (never {@code null})
	 */
	protected abstract Comparator<T> getMappingComparator(HttpServletRequest request);


	/**
	 * 一个注册表，维护到处理程序方法的所有映射，公开方法
	 * 执行查找并提供并发访问。
	 * <p>用于测试目的的包私有。
	 * A registry that maintains all mappings to handler methods, exposing methods
	 * to perform lookups and providing concurrent access.
	 * <p>Package-private for testing purposes.
	 */
	class MappingRegistry {

		/**
		 *  注册表
		 *
		 *   KEY: Mapping
		 */
		private final Map<T, MappingRegistration<T>> registry = new HashMap<>();

		/**
		 * 直接 URL 的映射
		 *
		 * KEY：直接 URL
		 * VALUE：Mapping 数组
		 */
		private final MultiValueMap<String, T> pathLookup = new LinkedMultiValueMap<>();

		/**
		 * Mapping 的名字与 HandlerMethod 的映射
		 *
		 * KEY：Mapping 的名字
		 * VALUE：HandlerMethod 数组
		 */
		private final Map<String, List<HandlerMethod>> nameLookup = new ConcurrentHashMap<>();
		/**
		 * 方法 cors配置映射
		 */
		private final Map<HandlerMethod, CorsConfiguration> corsLookup = new ConcurrentHashMap<>();
		/**
		 * 读写锁
		 */
		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

		/** 返回所有Mapping注册信息。
		 * Return all registrations.
		 * @since 5.3
		 */
		public Map<T, MappingRegistration<T>> getRegistrations() {
			return this.registry;
		}

		/** 返回给定 URL 路径的匹配项。不是线程安全的。
		 *
		 * Return matches for the given URL path. Not thread-safe.
		 * @see #acquireReadLock()
		 */
		@Nullable
		public List<T> getMappingsByDirectPath(String urlPath) {
			return this.pathLookup.get(urlPath);
		}

		/**
		 * Return handler methods by mapping name. Thread-safe for concurrent use.
		 */
		public List<HandlerMethod> getHandlerMethodsByMappingName(String mappingName) {
			return this.nameLookup.get(mappingName);
		}

		/**
		 * Return CORS configuration. Thread-safe for concurrent use.
		 */
		@Nullable
		public CorsConfiguration getCorsConfiguration(HandlerMethod handlerMethod) {
			HandlerMethod original = handlerMethod.getResolvedFromHandlerMethod();
			return this.corsLookup.get(original != null ? original : handlerMethod);
		}

		/** 使用 getMappings 和 getMappingsByUrl 时获取读锁。
		 * Acquire the read lock when using getMappings and getMappingsByUrl.
		 */
		public void acquireReadLock() {
			this.readWriteLock.readLock().lock();
		}

		/** 使用 getMappings 和 getMappingsByUrl 后释放读锁。
		 * Release the read lock after using getMappings and getMappingsByUrl.
		 */
		public void releaseReadLock() {
			this.readWriteLock.readLock().unlock();
		}

		/**
		 *注册 RequestMapping
		 */
		public void register(T mapping, Object handler, Method method) {
			// <1> 获得写锁
			this.readWriteLock.writeLock().lock();
			try {
				// <2.1> 创建 HandlerMethod 对象
				HandlerMethod handlerMethod = createHandlerMethod(handler, method);
				// <2.2> 校验当前 mapping 不存在，否则抛出 IllegalStateException 异常
				validateMethodMapping(handlerMethod, mapping);
				// <3.1> 获得 mapping 对应的普通 URL 数组
				Set<String> directPaths = AbstractHandlerMethodMapping.this.getDirectPaths(mapping);
				// <3.2> 添加到 url + mapping 到 urlLookup 集合中
				for (String path : directPaths) {
					this.pathLookup.add(path, mapping);
				}
				// <4> 初始化 nameLookup
				String name = null;
				if (getNamingStrategy() != null) {
					// <4.1> 获得 Mapping 的名字
					name = getNamingStrategy().getName(handlerMethod, mapping);
					// <4.2> 添加到 mapping 的名字 + HandlerMethod 到 nameLookup 中
					addMappingName(name, handlerMethod);
				}
				// <5>  init cors 将对应的方法或者类进行跨域配置解析 （如果有CrossOrigin注解在类或者方法上面的时候）
				CorsConfiguration corsConfig = initCorsConfiguration(handler, method, mapping);
				if (corsConfig != null) {
					corsConfig.validateAllowCredentials();
					this.corsLookup.put(handlerMethod, corsConfig);
				}
                 // <6> 创建 MappingRegistration 对象，并 mapping + MappingRegistration 添加到 registry 中
				this.registry.put(mapping,
						new MappingRegistration<>(mapping, handlerMethod, directPaths, name, corsConfig != null));
			}
			finally {
				// <7> 释放写锁
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void validateMethodMapping(HandlerMethod handlerMethod, T mapping) {
			MappingRegistration<T> registration = this.registry.get(mapping);
			HandlerMethod existingHandlerMethod = (registration != null ? registration.getHandlerMethod() : null);
			// 存在，且不相等，说明不唯一
			if (existingHandlerMethod != null && !existingHandlerMethod.equals(handlerMethod)) {
				throw new IllegalStateException(
						"Ambiguous mapping. Cannot map '" + handlerMethod.getBean() + "' method \n" +
						handlerMethod + "\nto " + mapping + ": There is already '" +
						existingHandlerMethod.getBean() + "' bean method\n" + existingHandlerMethod + " mapped.");
			}
		}

		private void addMappingName(String name, HandlerMethod handlerMethod) {
			// 获得 Mapping 的名字，对应的 HandlerMethod 数组
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				oldList = Collections.emptyList();
			}
           // 如果已经存在，则不用添加
			for (HandlerMethod current : oldList) {
				if (handlerMethod.equals(current)) {
					return;
				}
			}
			// 添加到 nameLookup 中
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() + 1);
			// 重新创建的原因是，保证数组的大小固定。因为，基本不太存在扩容的可能性，申请大了就浪费了
			newList.addAll(oldList);
			newList.add(handlerMethod);
			this.nameLookup.put(name, newList);
		}

		/**
		 * 取消注册RequestMapping
		 * @param mapping
		 */
		public void unregister(T mapping) {
			// 获得写锁
			this.readWriteLock.writeLock().lock();
			try {
				// 从 registry 中移除
				MappingRegistration<T> registration = this.registry.remove(mapping);
				if (registration == null) {
					return;
				}
				// 从 pathLookup 移除
				for (String path : registration.getDirectPaths()) {
					List<T> mappings = this.pathLookup.get(path);
					if (mappings != null) {
						mappings.remove(registration.getMapping());
						if (mappings.isEmpty()) {
							this.pathLookup.remove(path);
						}
					}
				}
				// 从 nameLookup 移除
				removeMappingName(registration);
				// 从 corsLookup 中移除
				this.corsLookup.remove(registration.getHandlerMethod());
			}
			finally {
				// 释放写锁
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void removeMappingName(MappingRegistration<T> definition) {
			String name = definition.getMappingName();
			if (name == null) {
				return;
			}
			HandlerMethod handlerMethod = definition.getHandlerMethod();
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				return;
			}
			if (oldList.size() <= 1) {
				this.nameLookup.remove(name);
				return;
			}
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() - 1);
			for (HandlerMethod current : oldList) {
				if (!current.equals(handlerMethod)) {
					newList.add(current);
				}
			}
			this.nameLookup.put(name, newList);
		}
	}


	static class MappingRegistration<T> {
		/**
		 * Mapping 对象
		 */
		private final T mapping;
		/**
		 * HandlerMethod 对象
		 */
		private final HandlerMethod handlerMethod;
		/**
		 * 直接 URL 数组
		 */
		private final Set<String> directPaths;
		/**
		 * {@link #mapping} 的名字
		 */
		@Nullable
		private final String mappingName;

		private final boolean corsConfig;

		public MappingRegistration(T mapping, HandlerMethod handlerMethod,
				@Nullable Set<String> directPaths, @Nullable String mappingName, boolean corsConfig) {

			Assert.notNull(mapping, "Mapping must not be null");
			Assert.notNull(handlerMethod, "HandlerMethod must not be null");
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
			this.directPaths = (directPaths != null ? directPaths : Collections.emptySet());
			this.mappingName = mappingName;
			this.corsConfig = corsConfig;
		}

		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public Set<String> getDirectPaths() {
			return this.directPaths;
		}

		@Nullable
		public String getMappingName() {
			return this.mappingName;
		}

		public boolean hasCorsConfig() {
			return this.corsConfig;
		}
	}


	/**
	 * A thin wrapper around a matched HandlerMethod and its mapping, for the purpose of
	 * comparing the best match with a comparator in the context of the current request.
	 */
	private class Match {
		/**
		 * Mapping 对象
		 */
		private final T mapping;
		/**
		 * Mapping注册信息 对象
		 */
		private final MappingRegistration<T> registration;

		public Match(T mapping, MappingRegistration<T> registration) {
			this.mapping = mapping;
			this.registration = registration;
		}

		public HandlerMethod getHandlerMethod() {
			return this.registration.getHandlerMethod();
		}

		public boolean hasCorsConfig() {
			return this.registration.hasCorsConfig();
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}


	private class MatchComparator implements Comparator<Match> {

		private final Comparator<T> comparator;

		public MatchComparator(Comparator<T> comparator) {
			this.comparator = comparator;
		}

		@Override
		public int compare(Match match1, Match match2) {
			return this.comparator.compare(match1.mapping, match2.mapping);
		}
	}


	private static class EmptyHandler {

		@SuppressWarnings("unused")
		public void handle() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

}
