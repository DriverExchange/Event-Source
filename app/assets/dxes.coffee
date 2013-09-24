
loadJSONP = do ->
	unique = 0

	(options) ->
		name = "_jsonp_" + unique++;
		url = options.url + (if options.url.indexOf("?") >= 0 then "&" else "?") + "callback=#{name}"

		script = document.createElement("script")
		script.type = "text/javascript"
		script.src = url
		script.async = true

		window[name] = ->
			options.callback.apply(null, arguments)
			document.getElementsByTagName("head")[0].removeChild(script)
			script = null

		document.getElementsByTagName("head")[0].appendChild(script)

getParams = (options) ->
	params = "?ts=#{new Date().getTime()}"
	if options.filters && options.signature
		params += "&filters=#{options.filters}&signature=#{options.signature}"
	params

window.dxes = (baseUrl, appId) ->

	subscribeSSE = (options) ->
		productEvents = new EventSource(baseUrl + "/#{appId}/events/#{options.channel}" + getParams(options))
		productEvents.addEventListener "message", (event) ->
			data = null
			eval("data = #{event.data};")
			options.received(data)

	subscribeJsonp = (options) ->
		timeoutId = null
		poll = ->
			loadJSONP
				url: baseUrl + "/#{appId}/events/#{options.channel}/comet" + getParams(options)
				callback: (status, data) ->
					clearTimeout(timeoutId)
					if status == "success"
						options.received(data)
						poll()

			timeoutId = setTimeout(poll, 60 * 1000)

		# use setTimeout here otherwise the browser loading icon won't stop spinning
		setTimeout(poll, 1)

	subscribeSSE: subscribeSSE
	subscribeJsonp: subscribeJsonp
	subscribe: (options) ->
		if !!window["EventSource"]
			try
				subscribeSSE(options)
			catch e
				subscribeJsonp(options)
		else
			subscribeJsonp(options)

window.dxesInit?()
