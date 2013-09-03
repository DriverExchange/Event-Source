window.dxio = (appId) ->

	subscribeSSE: (channelName, callback) ->
		productEvents = new EventSource("http://localhost:9001/#{appId}/events/#{channelName}")
		productEvents.addEventListener "message", (event) ->
			data = null
			eval("data = #{event.data};")
			callback(data)

	subscribeComet: (channelName, callback) ->
		polling = false
		poll = ->
			polling = true
			$.ajax
				type: "get"
				url: "http://localhost:9001/#{appId}/events/#{channelName}/comet"
				dataType: "jsonp"
				jsonp: "callback"
				success: (data) ->
					callback(data)
				complete: (jqXHR, textStatus) ->
					$("body").prepend("<div>#{textStatus}</div>");
					if textStatus == "timeout"
						alreadyPolling = false
						if !alreadyPolling
							poll()
							alreadyPolling = true
					else if textStatus == "success" or textStatus == "notmodified"
						poll()
					else
						polling = false
		poll()

	subscribe: (channelName, callback) ->
		if EventSource
			dxio.subscribeSSE(channelName, callback)
		else
			dxio.subscribeComet(channelName, callback)


