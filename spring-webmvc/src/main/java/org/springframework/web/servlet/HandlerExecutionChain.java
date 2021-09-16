/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.web.servlet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;

/**
 * Handler execution chain, consisting of handler object and any handler interceptors.
 * Returned by HandlerMapping's {@link HandlerMapping#getHandler} method.
 *
 * @author Juergen Hoeller
 * @since 20.06.2003
 * @see HandlerInterceptor
 */
public class HandlerExecutionChain {

	private static final Log logger = LogFactory.getLog(HandlerExecutionChain.class);

	/**
	 * 处理器
	 */
	private final Object handler;

	/**
	 * 拦截器数组
	 */
	private final List<HandlerInterceptor> interceptorList = new ArrayList<>();

	/**
	 * 已执行 {@link HandlerInterceptor#preHandle(HttpServletRequest, HttpServletResponse, Object)} 的位置
	 *
	 * 主要用于实现 {@link #applyPostHandle(HttpServletRequest, HttpServletResponse, ModelAndView)} 的逻辑
	 */
	private int interceptorIndex = -1;


	/**
	 * Create a new HandlerExecutionChain.
	 * @param handler the handler object to execute
	 */
	public HandlerExecutionChain(Object handler) {
		this(handler, (HandlerInterceptor[]) null);
	}

	/**
	 * Create a new HandlerExecutionChain.
	 * @param handler the handler object to execute
	 * @param interceptors the array of interceptors to apply
	 * (in the given order) before the handler itself executes
	 */
	public HandlerExecutionChain(Object handler, @Nullable HandlerInterceptor... interceptors) {
		this(handler, (interceptors != null ? Arrays.asList(interceptors) : Collections.emptyList()));
	}

	/**
	 * Create a new HandlerExecutionChain.
	 * @param handler the handler object to execute
	 * @param interceptorList the list of interceptors to apply
	 * (in the given order) before the handler itself executes
	 * @since 5.3
	 */
	public HandlerExecutionChain(Object handler, List<HandlerInterceptor> interceptorList) {
		if (handler instanceof HandlerExecutionChain) {
			HandlerExecutionChain originalChain = (HandlerExecutionChain) handler;
			this.handler = originalChain.getHandler();
			// 初始化到 interceptorList 中
			this.interceptorList.addAll(originalChain.interceptorList);
		}
		else {
			this.handler = handler;
		}
		this.interceptorList.addAll(interceptorList);
	}


	/** 返回要执行的处理程序对象。
	 * Return the handler object to execute.
	 */
	public Object getHandler() {
		return this.handler;
	}

	/** 将给定的拦截器添加到此链的末尾。
	 * Add the given interceptor to the end of this chain.
	 */
	public void addInterceptor(HandlerInterceptor interceptor) {
		this.interceptorList.add(interceptor);
	}

	/** 在此链的指定索引处添加给定的拦截器。
	 * Add the given interceptor at the specified index of this chain.
	 * @since 5.2
	 */
	public void addInterceptor(int index, HandlerInterceptor interceptor) {
		this.interceptorList.add(index, interceptor);
	}

	/** 将给定的拦截器添加到此链的末尾。
	 * Add the given interceptors to the end of this chain.
	 */
	public void addInterceptors(HandlerInterceptor... interceptors) {
		CollectionUtils.mergeArrayIntoCollection(interceptors, this.interceptorList);
	}

	/** 返回要应用的拦截器数组（按给定顺序）。
	 * Return the array of interceptors to apply (in the given order).
	 * @return the array of HandlerInterceptors instances (may be {@code null})
	 */
	@Nullable
	public HandlerInterceptor[] getInterceptors() {
		// 将 interceptorList 转化成数组返回
		return (!this.interceptorList.isEmpty() ? this.interceptorList.toArray(new HandlerInterceptor[0]) : null);
	}

	/** 返回要应用的拦截器数组（按给定顺序）。
	 * Return the list of interceptors to apply (in the given order).
	 * @return the list of HandlerInterceptors instances (potentially empty)
	 * @since 5.3
	 */
	public List<HandlerInterceptor> getInterceptorList() {
		return (!this.interceptorList.isEmpty() ? Collections.unmodifiableList(this.interceptorList) :
				Collections.emptyList());
	}


	/** 应用拦截器的前置处理
	 *
	 * Apply preHandle methods of registered interceptors.
	 * @return {@code true} if the execution chain should proceed with the
	 * next interceptor or the handler itself. Else, DispatcherServlet assumes
	 * that this interceptor has already dealt with the response itself.
	 */
	boolean applyPreHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
		// <1> 获得拦截器数组,进行遍历
		for (int i = 0; i < this.interceptorList.size(); i++) {
			HandlerInterceptor interceptor = this.interceptorList.get(i);
			// <3> 前置处理
			if (!interceptor.preHandle(request, response, this.handler)) {
				// <3.1> 触发已完成处理
				triggerAfterCompletion(request, response, null);
				// 返回 false ，前置处理失败
				return false;
			}
			// <3.2> 标记 interceptorIndex 位置
			this.interceptorIndex = i;
		}
		// <4> 返回 true ，前置处理成功
		return true;
	}

	/** 应用已注册拦截器的 postHandle 方法。
	 * Apply postHandle methods of registered interceptors.
	 */
	void applyPostHandle(HttpServletRequest request, HttpServletResponse response, @Nullable ModelAndView mv)
			throws Exception {
		// 遍历拦截器数组
		for (int i = this.interceptorList.size() - 1; i >= 0; i--) { // 倒序
			HandlerInterceptor interceptor = this.interceptorList.get(i);
			// 后置处理
			interceptor.postHandle(request, response, this.handler, mv);
		}
	}

	/**
	 *在映射的 HandlerInterceptors 上触发 afterCompletion 回调。
	 * 将只为 preHandle 调用已成功完成并返回 true 的所有拦截器调用 afterCompletion。
	 *
	 * Trigger afterCompletion callbacks on the mapped HandlerInterceptors.
	 * Will just invoke afterCompletion for all interceptors whose preHandle invocation
	 * has successfully completed and returned true.
	 */
	void triggerAfterCompletion(HttpServletRequest request, HttpServletResponse response, @Nullable Exception ex) {
		// 遍历拦截器数组
		for (int i = this.interceptorIndex; i >= 0; i--) {  // 倒序！！！
			HandlerInterceptor interceptor = this.interceptorList.get(i);
			try {
				// 已完成处理
				interceptor.afterCompletion(request, response, this.handler, ex);
			}
			catch (Throwable ex2) { // 注意，如果执行失败，仅仅会打印错误日志，不会结束循环
				logger.error("HandlerInterceptor.afterCompletion threw exception", ex2);
			}
		}
	}

	/** 在映射的 AsyncHandlerInterceptors 上应用 afterConcurrentHandlerStarted 回调。
	 * Apply afterConcurrentHandlerStarted callback on mapped AsyncHandlerInterceptors.
	 */
	void applyAfterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response) {
		for (int i = this.interceptorList.size() - 1; i >= 0; i--) {
			HandlerInterceptor interceptor = this.interceptorList.get(i);
			if (interceptor instanceof AsyncHandlerInterceptor) {
				try {
					AsyncHandlerInterceptor asyncInterceptor = (AsyncHandlerInterceptor) interceptor;
					asyncInterceptor.afterConcurrentHandlingStarted(request, response, this.handler);
				}
				catch (Throwable ex) {
					if (logger.isErrorEnabled()) {
						logger.error("Interceptor [" + interceptor + "] failed in afterConcurrentHandlingStarted", ex);
					}
				}
			}
		}
	}


	/**
	 * Delegates to the handler's {@code toString()} implementation.
	 */
	@Override
	public String toString() {
		return "HandlerExecutionChain with [" + getHandler() + "] and " + this.interceptorList.size() + " interceptors";
	}

}
