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

package org.springframework.web.method.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ExceptionDepthComparator;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Discovers {@linkplain ExceptionHandler @ExceptionHandler} methods in a given class,
 * including all of its superclasses, and helps to resolve a given {@link Exception}
 * to the exception types supported by a given {@link Method}.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 */
public class ExceptionHandlerMethodResolver {

	/**
	 * A filter for selecting {@code @ExceptionHandler} methods.
	 *  MethodFilter 对象，用于过滤带有 @ExceptionHandler 注解的方法
	 */
	public static final MethodFilter EXCEPTION_HANDLER_METHODS = method ->
			AnnotatedElementUtils.hasAnnotation(method, ExceptionHandler.class);

	private static final Method NO_MATCHING_EXCEPTION_HANDLER_METHOD;

	static {
		try {
			NO_MATCHING_EXCEPTION_HANDLER_METHOD =
					ExceptionHandlerMethodResolver.class.getDeclaredMethod("noMatchingExceptionHandler");
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException("Expected method not found: " + ex);
		}
	}

	/**
	 * 已经映射的方法
	 *
	 * 在 {@link #ExceptionHandlerMethodResolver(Class)} 构造方法中初始化
	 */
	private final Map<Class<? extends Throwable>, Method> mappedMethods = new HashMap<>(16);
	/**
	 * 已经匹配的方法
	 *
	 * 在 {@link #resolveMethod(Exception)} 方法中初始化
	 */
	private final Map<Class<? extends Throwable>, Method> exceptionLookupCache = new ConcurrentReferenceHashMap<>(16);


	/**
	 * A constructor that finds {@link ExceptionHandler} methods in the given type.
	 * @param handlerType the type to introspect
	 */
	public ExceptionHandlerMethodResolver(Class<?> handlerType) {
		// <1> 遍历某个类下面 @ExceptionHandler 注解的方法
		for (Method method : MethodIntrospector.selectMethods(handlerType, EXCEPTION_HANDLER_METHODS)) {
			// <2> 遍历处理的异常集合
			for (Class<? extends Throwable> exceptionType : detectExceptionMappings(method)) {
				// <3> 添加到 mappedMethods 中
				addExceptionMapping(exceptionType, method);
			}
		}
	}


	/** 首先从 {@code @ExceptionHandler} 注释中提取异常映射，然后作为方法签名本身的后备。
	 *
	 * Extract exception mappings from the {@code @ExceptionHandler} annotation first,
	 * and then as a fallback from the method signature itself.
	 */
	@SuppressWarnings("unchecked")
	private List<Class<? extends Throwable>> detectExceptionMappings(Method method) {
		List<Class<? extends Throwable>> result = new ArrayList<>();
		// 首先，从方法上的 @ExceptionHandler 注解中，获得所处理的异常，添加到 result 中
		detectAnnotationExceptionMappings(method, result);
		// 其次，如果获取不到，从方法参数中，获得所处理的异常，添加到 result 中
		if (result.isEmpty()) {
			for (Class<?> paramType : method.getParameterTypes()) {
				//如果ExceptionHandler指定的异常和方法的参数异常一致
				if (Throwable.class.isAssignableFrom(paramType)) {
					result.add((Class<? extends Throwable>) paramType);
				}
			}
		}
		// 如果获取不到，则抛出 IllegalStateException 异常
		if (result.isEmpty()) {
			throw new IllegalStateException("No exception types mapped to " + method);
		}
		return result;
	}

	/**
	 * @ControllerAdvice
	 * public class GlobalExceptionHandler {
	 *    //处理自定义的异常
	 *    @ExceptionHandler(SystemException.class)
	 *    @ResponseBody
	 *    public Object customHandler(SystemException e){
	 *       e.printStackTrace();
	 *       return WebResult.buildResult().status(e.getCode()).msg(e.getMessage());
	 *    }
	 *    //其他未处理的异常
	 *    @ExceptionHandler(Exception.class)
	 *    @ResponseBody
	 *    public Object exceptionHandler(Exception e){
	 *       e.printStackTrace();
	 *       return WebResult.buildResult().status(Config.FAIL).msg("系统错误");
	 *    }
	 * }
	 * 提取ExceptionHandler中的异常信息 SystemException.class，Exception.class
	 * @param method
	 * @param result
	 */
	private void detectAnnotationExceptionMappings(Method method, List<Class<? extends Throwable>> result) {
		ExceptionHandler ann = AnnotatedElementUtils.findMergedAnnotation(method, ExceptionHandler.class);
		Assert.state(ann != null, "No ExceptionHandler annotation");
		result.addAll(Arrays.asList(ann.value()));
	}

	private void addExceptionMapping(Class<? extends Throwable> exceptionType, Method method) {
		// 添加到 mappedMethods 中
		Method oldMethod = this.mappedMethods.put(exceptionType, method);
		// 如果已存在，说明冲突，所以抛出 IllegalStateException 异常
		if (oldMethod != null && !oldMethod.equals(method)) {
			throw new IllegalStateException("Ambiguous @ExceptionHandler method mapped for [" +
					exceptionType + "]: {" + oldMethod + ", " + method + "}");
		}
	}

	/** 否有任何异常映射。
	 * Whether the contained type has any exception mappings.
	 */
	public boolean hasExceptionMappings() {
		return !this.mappedMethods.isEmpty();
	}

	/** 找到一个 {@link Method} 来处理给定的异常。
	 *
	 * Find a {@link Method} to handle the given exception.
	 * <p>Uses {@link ExceptionDepthComparator} if more than one match is found.
	 * @param exception the exception
	 * @return a Method to handle the exception, or {@code null} if none found
	 */
	@Nullable
	public Method resolveMethod(Exception exception) {
		return resolveMethodByThrowable(exception);
	}

	/**
	 * Find a {@link Method} to handle the given Throwable.
	 * <p>Uses {@link ExceptionDepthComparator} if more than one match is found.
	 * @param exception the exception
	 * @return a Method to handle the exception, or {@code null} if none found
	 * @since 5.0
	 */
	@Nullable
	public Method resolveMethodByThrowable(Throwable exception) {
		// 首先，获得异常对应的方法
		Method method = resolveMethodByExceptionType(exception.getClass());
		if (method == null) {
			// 其次，获取不到，则使用异常 cause 对应的方法
			Throwable cause = exception.getCause();
			if (cause != null) {
				method = resolveMethodByThrowable(cause);
			}
		}
		return method;
	}

	/**
	 * Find a {@link Method} to handle the given exception type. This can be
	 * useful if an {@link Exception} instance is not available (e.g. for tools).
	 * <p>Uses {@link ExceptionDepthComparator} if more than one match is found.
	 * @param exceptionType the exception type
	 * @return a Method to handle the exception, or {@code null} if none found
	 */
	@Nullable
	public Method resolveMethodByExceptionType(Class<? extends Throwable> exceptionType) {
		// 首先，先从 exceptionLookupCache 缓存中获得
		Method method = this.exceptionLookupCache.get(exceptionType);
		if (method == null) {
			// 其次，获取不到，则从 mappedMethods 中获得，并添加到 exceptionLookupCache 中
			method = getMappedMethod(exceptionType);
			this.exceptionLookupCache.put(exceptionType, method);
		}
		return (method != NO_MATCHING_EXCEPTION_HANDLER_METHOD ? method : null);
	}

	/**
	 * Return the {@link Method} mapped to the given exception type, or
	 * {@link #NO_MATCHING_EXCEPTION_HANDLER_METHOD} if none.
	 */
	private Method getMappedMethod(Class<? extends Throwable> exceptionType) {
		List<Class<? extends Throwable>> matches = new ArrayList<>();
		// 遍历 mappedMethods 数组，匹配异常，添加到 matches 中
		for (Class<? extends Throwable> mappedException : this.mappedMethods.keySet()) {
			if (mappedException.isAssignableFrom(exceptionType)) {
				matches.add(mappedException);
			}
		}
		// 将匹配的结果，排序，选择第一个
		if (!matches.isEmpty()) {
			if (matches.size() > 1) {
				matches.sort(new ExceptionDepthComparator(exceptionType));
			}
			return this.mappedMethods.get(matches.get(0));
		}
		else {
			return NO_MATCHING_EXCEPTION_HANDLER_METHOD;
		}
	}

	/**
	 * For the {@link #NO_MATCHING_EXCEPTION_HANDLER_METHOD} constant.
 	 */
	@SuppressWarnings("unused")
	private void noMatchingExceptionHandler() {
	}

}
