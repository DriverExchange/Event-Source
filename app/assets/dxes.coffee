
loadJSONP = do ->
	unique = 0

	(options) ->
		name = "_jsonp_" + unique++;
		url = options.url + (if options.url.indexOf("?") >= 0 then "&" else "?") + "callback=#{name}"
		url += "&timestamp=" + new Date().getTime()

		script = document.createElement("script")
		script.type = "text/javascript"
		script.src = url

		window[name] = ->
			options.callback.apply(null, arguments)
			document.getElementsByTagName("head")[0].removeChild(script)
			script = null

		document.getElementsByTagName("head")[0].appendChild(script)

window.dxes = (appId) ->

	subscribeSSE = (channelName, callback) ->
		productEvents = new EventSource("http://localhost:9001/#{appId}/events/#{channelName}")
		productEvents.addEventListener "message", (event) ->
			data = null
			eval("data = #{event.data};")
			callback(data)

	subscribeJsonp = (channelName, callback) ->
		timeoutId = null
		poll = ->
			script = document.getElementById("dxesPollLoaded")
			loadJSONP
				url: "http://localhost:9001/#{appId}/events/#{channelName}/comet"
				callback: (status, data) ->
					callback(data) if status == "success"
					clearTimeout(timeoutId)
					poll()

			timeoutId = setTimeout(poll, 60 * 1000)

		poll()

	subscribeSSE: subscribeSSE
	subscribeJsonp: subscribeJsonp
	subscribe: (channelName, callback) ->
		if EventSource
			subscribeSSE(channelName, callback)
		else
			subscribeJsonp(channelName, callback)



