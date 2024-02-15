package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.api.model.crosschain.TradeBotRespondRequest;
import org.qortal.asset.Asset;
import org.qortal.controller.Controller;
import org.qortal.controller.tradebot.AcctTradeBot;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.AcctMode;
import org.qortal.crosschain.ForeignBlockchain;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Path("/crosschain/tradebot")
@Tag(name = "Cross-Chain (Trade-Bot)")
public class CrossChainTradeBotResource {

	@Context
	HttpServletRequest request;

	@GET
	@Operation(
		summary = "List current trade-bot states",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TradeBotData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<TradeBotData> getTradeBotStates(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(
					description = "Limit to specific blockchain",
					example = "LITECOIN",
					schema = @Schema(implementation = SupportedBlockchain.class)
				) @QueryParam("foreignBlockchain") SupportedBlockchain foreignBlockchain) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();

			if (foreignBlockchain == null)
				return allTradeBotData;

			return allTradeBotData.stream().filter(tradeBotData -> tradeBotData.getForeignBlockchain().equals(foreignBlockchain.name())).collect(Collectors.toList());
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/create")
	@Operation(
		summary = "Create a trade offer (trade-bot entry)",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TradeBotCreateRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.INSUFFICIENT_BALANCE, ApiError.REPOSITORY_ISSUE, ApiError.ORDER_SIZE_TOO_SMALL})
	@SuppressWarnings("deprecation")
	@SecurityRequirement(name = "apiKey")
	public String tradeBotCreator(@HeaderParam(Security.API_KEY_HEADER) String apiKey, TradeBotCreateRequest tradeBotCreateRequest) {
		Security.checkApiCallAllowed(request);

		if (tradeBotCreateRequest.foreignBlockchain == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		ForeignBlockchain foreignBlockchain = tradeBotCreateRequest.foreignBlockchain.getInstance();

		// We prefer foreignAmount to deprecated bitcoinAmount
		if (tradeBotCreateRequest.foreignAmount == null)
			tradeBotCreateRequest.foreignAmount = tradeBotCreateRequest.bitcoinAmount;

		if (!foreignBlockchain.isValidAddress(tradeBotCreateRequest.receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (tradeBotCreateRequest.tradeTimeout < 60)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (tradeBotCreateRequest.foreignAmount == null || tradeBotCreateRequest.foreignAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

		if (tradeBotCreateRequest.foreignAmount < foreignBlockchain.getMinimumOrderAmount())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

		if (tradeBotCreateRequest.qortAmount <= 0 || tradeBotCreateRequest.fundingQortAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

		final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
		if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Do some simple checking first
			Account creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);

			if (creator.getConfirmedBalance(Asset.QORT) < tradeBotCreateRequest.fundingQortAmount)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INSUFFICIENT_BALANCE);

			byte[] unsignedBytes = TradeBot.getInstance().createTrade(repository, tradeBotCreateRequest);
			if (unsignedBytes == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			return Base58.encode(unsignedBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/respond")
	@Operation(
		summary = "Respond to a trade offer. NOTE: WILL SPEND FUNDS!)",
		description = "Start a new trade-bot entry to respond to chosen trade offer.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TradeBotRespondRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, ApiError.REPOSITORY_ISSUE})
	@SuppressWarnings("deprecation")
	@SecurityRequirement(name = "apiKey")
	public String tradeBotResponder(@HeaderParam(Security.API_KEY_HEADER) String apiKey, TradeBotRespondRequest tradeBotRespondRequest) {
		Security.checkApiCallAllowed(request);

		final String atAddress = tradeBotRespondRequest.atAddress;

		// We prefer foreignKey to deprecated xprv58
		if (tradeBotRespondRequest.foreignKey == null)
			tradeBotRespondRequest.foreignKey = tradeBotRespondRequest.xprv58;

		if (tradeBotRespondRequest.foreignKey == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		if (atAddress == null || !Crypto.isValidAtAddress(atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (tradeBotRespondRequest.receivingAddress == null || !Crypto.isValidAddress(tradeBotRespondRequest.receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
		if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, atAddress);

			// TradeBot uses AT's code hash to map to ACCT
			ACCT acct = TradeBot.getInstance().getAcctUsingAtData(atData);
			if (acct == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			if (!acct.getBlockchain().isValidWalletKey(tradeBotRespondRequest.foreignKey))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
			if (crossChainTradeData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			if (crossChainTradeData.mode != AcctMode.OFFERING)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Check if there is a buy or a cancel request in progress for this trade
			List<Transaction.TransactionType> txTypes = List.of(Transaction.TransactionType.MESSAGE);
			List<TransactionData> unconfirmed = repository.getTransactionRepository().getUnconfirmedTransactions(txTypes, null, 0, 0, false);
			for (TransactionData  transactionData : unconfirmed) {
				MessageTransactionData messageTransactionData = (MessageTransactionData) transactionData;
				if (Objects.equals(messageTransactionData.getRecipient(), atAddress)) {
					// There is a pending request for this trade, so block this buy attempt to reduce the risk of refunds
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Trade has an existing buy request or is pending cancellation.");
				}
			}

			AcctTradeBot.ResponseResult result = TradeBot.getInstance().startResponse(repository, atData, acct, crossChainTradeData,
					tradeBotRespondRequest.foreignKey, tradeBotRespondRequest.receivingAddress);

			switch (result) {
				case OK:
					return "true";

				case BALANCE_ISSUE:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE);

				case NETWORK_ISSUE:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

				default:
					return "false";
			}
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}

	@DELETE
	@Operation(
		summary = "Delete completed trade",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					example = "93MB2qRDNVLxbmmPuYpLdAqn3u2x9ZhaVZK5wELHueP8"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String tradeBotDelete(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String tradePrivateKey58) {
		Security.checkApiCallAllowed(request);

		final byte[] tradePrivateKey;
		try {
			tradePrivateKey = Base58.decode(tradePrivateKey58);

			if (tradePrivateKey.length != 32)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Handed off to TradeBot
			return TradeBot.getInstance().deleteEntry(repository, tradePrivateKey) ? "true" : "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	private ATData fetchAtDataWithChecking(Repository repository, String atAddress) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		if (atData == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

		// No point sending message to AT that's finished
		if (atData.getIsFinished())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return atData;
	}

}
