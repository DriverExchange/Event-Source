
loadJSONP = do ->
	unique = 0;

	(options) ->

		name = "_jsonp_" + unique++;
		url = options.url + (if options.url.indexOf("?") >= 0 then "&" else "?") + "callback=#{name}"

		script = document.createElement('script');
		script.type = 'text/javascript';
		script.src = url;

		window[name] = (data) ->
			options.callback(data)
			document.getElementsByTagName('head')[0].removeChild(script)
			script = null
			delete window[name]

		document.getElementsByTagName('head')[0].appendChild(script)

window.dxio = (appId) ->

	subscribeSSE = (channelName, callback) ->
		productEvents = new EventSource("http://localhost:9001/#{appId}/events/#{channelName}")
		productEvents.addEventListener "message", (event) ->
			data = null
			eval("data = #{event.data};")
			callback(data)

	subscribeJsonp = (channelName, callback) ->
		polling = false
		poll = ->
			polling = true
			loadJSONP
				url: "http://localhost:9001/#{appId}/events/#{channelName}/comet"
				callback: (data) ->
					callback(data)
					poll()
		poll()

	subscribeSSE: subscribeSSE
	subscribeJsonp: subscribeJsonp
	subscribe: (channelName, callback) ->
		if EventSource
			subscribeSSE(channelName, callback)
		else
			subscribeJsonp(channelName, callback)



