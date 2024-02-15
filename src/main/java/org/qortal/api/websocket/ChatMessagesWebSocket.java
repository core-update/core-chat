package org.qortal.api.websocket;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.controller.ChatNotifier;
import org.qortal.data.chat.ChatMessage;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

import static org.qortal.data.chat.ChatMessage.Encoding;

@WebSocket
@SuppressWarnings("serial")
public class ChatMessagesWebSocket extends ApiWebSocket {

	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(ChatMessagesWebSocket.class);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		Encoding encoding = getTargetEncoding(session);

		List<String> limitList = queryParams.get("limit");
		Integer limit = (limitList != null && limitList.size() == 1) ? Integer.parseInt(limitList.get(0)) : null;

		List<String> offsetList = queryParams.get("offset");
		Integer offset = (offsetList != null && offsetList.size() == 1) ? Integer.parseInt(offsetList.get(0)) : null;

		List<String> reverseList = queryParams.get("offset");
		Boolean reverse = (reverseList != null && reverseList.size() == 1) ? Boolean.getBoolean(reverseList.get(0)) : null;

		List<String> txGroupIds = queryParams.get("txGroupId");
		if (txGroupIds != null && txGroupIds.size() == 1) {
			int txGroupId = Integer.parseInt(txGroupIds.get(0));

			try (final Repository repository = RepositoryManager.getRepository()) {
				List<ChatMessage> chatMessages = repository.getChatRepository().getMessagesMatchingCriteria(
						null,
						null,
						txGroupId,
						null,
						null,
						null,
						null,
						null,
						encoding,
						limit, offset, reverse);

				sendMessages(session, chatMessages);
			} catch (DataException e) {
				// Not a good start
				session.close(4001, "Couldn't fetch initial messages from repository");
				return;
			}

			ChatNotifier.Listener listener = chatMessage -> onNotify(session, chatMessage, txGroupId);
			ChatNotifier.getInstance().register(session, listener);

			return;
		}

		List<String> involvingAddresses = queryParams.get("involving");
		if (involvingAddresses == null || involvingAddresses.size() != 2) {
			session.close(4001, "invalid criteria");
			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<ChatMessage> chatMessages = repository.getChatRepository().getMessagesMatchingCriteria(
					null,
					null,
					null,
					null,
					null,
					null,
					involvingAddresses,
					null,
					encoding,
					limit, offset, reverse);

			sendMessages(session, chatMessages);
		} catch (DataException e) {
			// Not a good start
			session.close(4001, "Couldn't fetch initial messages from repository");
			return;
		}

		ChatNotifier.Listener listener = chatMessage -> onNotify(session, chatMessage, involvingAddresses);
		ChatNotifier.getInstance().register(session, listener);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		ChatNotifier.getInstance().deregister(session);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* ignored */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		if (Objects.equals(message, "ping")) {
			session.getRemote().sendStringByFuture("pong");
		}
	}

	private void onNotify(Session session, ChatMessage chatMessage, int txGroupId) {
		if (chatMessage == null)
			// There has been a group-membership change, but we're not interested
			return;

		// We only want group-based messages with our txGroupId
		if (chatMessage.getRecipient() != null || chatMessage.getTxGroupId() != txGroupId)
			return;

		sendChat(session, chatMessage);
	}

	private void onNotify(Session session, ChatMessage chatMessage, List<String> involvingAddresses) {
		if (chatMessage == null)
			return;

		// We only want direct/non-group messages where sender/recipient match our addresses
		String recipient = chatMessage.getRecipient();
		if (recipient == null)
			return;

		List<String> transactionAddresses = Arrays.asList(recipient, chatMessage.getSender());

		if (!transactionAddresses.containsAll(involvingAddresses))
			return;

		sendChat(session, chatMessage);
	}

	private void sendMessages(Session session, List<ChatMessage> chatMessages) {
		StringWriter stringWriter = new StringWriter();

		try {
			marshall(stringWriter, chatMessages);

			session.getRemote().sendStringByFuture(stringWriter.toString());
		} catch (IOException | WebSocketException e) {
			// No output this time?
		}
	}

	private void sendChat(Session session, ChatMessage chatMessage) {
		sendMessages(session, Collections.singletonList(chatMessage));
	}

	private Encoding getTargetEncoding(Session session) {
		// Default to Base58 if not specified, for backwards support
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		List<String> encodingList = queryParams.get("encoding");
		String encoding = (encodingList != null && encodingList.size() == 1) ? encodingList.get(0) : "BASE58";
		return Encoding.valueOf(encoding);
	}

}
