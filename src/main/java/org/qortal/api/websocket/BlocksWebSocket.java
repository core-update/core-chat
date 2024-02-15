package org.qortal.api.websocket;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.api.ApiError;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

@WebSocket
@SuppressWarnings("serial")
public class BlocksWebSocket extends ApiWebSocket implements Listener {

	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(BlocksWebSocket.class);

		EventBus.INSTANCE.addListener(this::listen);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof Controller.NewBlockEvent))
			return;

		BlockData blockData = ((Controller.NewBlockEvent) event).getBlockData();
		BlockSummaryData blockSummary = new BlockSummaryData(blockData);

		for (Session session : getSessions())
			sendBlockSummary(session, blockSummary);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		super.onWebSocketConnect(session);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* We ignore errors for now, but method here to silence log spam */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		// We're expecting either a base58 block signature or an integer block height
		if (message.length() > 128) {
			// Try base58 block signature
			byte[] signature;

			try {
				signature = Base58.decode(message);
			} catch (NumberFormatException e) {
				sendError(session, ApiError.INVALID_SIGNATURE);
				return;
			}

			try (final Repository repository = RepositoryManager.getRepository()) {
				int height = repository.getBlockRepository().getHeightFromSignature(signature);
				if (height == 0) {
					sendError(session, ApiError.BLOCK_UNKNOWN);
					return;
				}

				List<BlockSummaryData> blockSummaries = repository.getBlockRepository().getBlockSummaries(height, height);
				if (blockSummaries == null || blockSummaries.isEmpty()) {
					sendError(session, ApiError.BLOCK_UNKNOWN);
					return;
				}

				sendBlockSummary(session, blockSummaries.get(0));
			} catch (DataException e) {
				sendError(session, ApiError.REPOSITORY_ISSUE);
			}

			return;
		}

		if (message.length() > 10)
			// Bigger than max integer value, so probably a ping - silently ignore
			return;

		// Try integer
		int height;

		try {
			height = Integer.parseInt(message);
		} catch (NumberFormatException e) {
			sendError(session, ApiError.INVALID_HEIGHT);
			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<BlockSummaryData> blockSummaries = repository.getBlockRepository().getBlockSummaries(height, height);
			if (blockSummaries == null || blockSummaries.isEmpty()) {
				sendError(session, ApiError.BLOCK_UNKNOWN);
				return;
			}

			sendBlockSummary(session, blockSummaries.get(0));
		} catch (DataException e) {
			sendError(session, ApiError.REPOSITORY_ISSUE);
		}
	}

	private void sendBlockSummary(Session session, BlockSummaryData blockSummary) {
		StringWriter stringWriter = new StringWriter();

		try {
			marshall(stringWriter, blockSummary);

			session.getRemote().sendStringByFuture(stringWriter.toString());
		} catch (IOException | WebSocketException e) {
			// No output this time
		}
	}

}
