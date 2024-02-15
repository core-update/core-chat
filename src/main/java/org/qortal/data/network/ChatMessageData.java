package org.qortal.data.network;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.AccessMode;
import org.qortal.data.transaction.TransactionData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
public class ChatMessageData {

	// Properties
	@Schema(description = "timestamp when message created, in milliseconds since unix epoch", example = "__unix_epoch_time_milliseconds__")
	protected long timestamp;

	@Schema(description = "groupID for this message")
	protected int txGroupId; // TODO: rename?

	@Schema(description = "sender's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] senderPublicKey;

	@Schema(accessMode = AccessMode.READ_ONLY)
	private String sender;

	@Schema(accessMode = AccessMode.READ_ONLY)
	private int nonce;

	private String recipient; // can be null

	@Schema(description = "raw message data, possibly UTF8 text", example = "2yGEbwRFyhPZZckKA")
	private byte[] data;

	private boolean isText;
	private boolean isEncrypted;

	// Constructors

	// For JAXB
	protected ChatMessageData() {}

	public ChatMessageData(long timestamp, int txGroupId, byte[] senderPublicKey,
                           String sender, int nonce, String recipient, byte[] data, boolean isText, boolean isEncrypted) {

		this.timestamp = timestamp;
		this.txGroupId = txGroupId;
		this.senderPublicKey = senderPublicKey;
		this.sender = sender;
		this.nonce = nonce;
		this.recipient = recipient;
		this.data = data;
		this.isText = isText;
		this.isEncrypted = isEncrypted;
	}

	// Getters/Setters

	public long getTimestamp() {
		return this.timestamp;
	}

	public int getTxGroupId() {
		return this.txGroupId;
	}

	public byte[] getSenderPublicKey() {
		return this.senderPublicKey;
	}

	public String getSender() {
		return this.sender;
	}

	public int getNonce() {
		return this.nonce;
	}

	public void setNonce(int nonce) {
		this.nonce = nonce;
	}

	public String getRecipient() {
		return this.recipient;
	}

	public byte[] getData() {
		return this.data;
	}

	public boolean getIsText() {
		return this.isText;
	}

	public boolean getIsEncrypted() {
		return this.isEncrypted;
	}

}
