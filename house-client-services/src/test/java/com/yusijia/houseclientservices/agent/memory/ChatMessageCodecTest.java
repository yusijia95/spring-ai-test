package com.yusijia.houseclientservices.agent.memory;

import com.yusijia.houseclientservices.agent.memory.redis.ChatMessageCodec;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatMessageCodecTest {

    private final ChatMessageCodec codec = new ChatMessageCodec();

    @Test
    void 用户消息往返保持类型与文本() {
        Message decoded = codec.decode(codec.encode(new UserMessage("我想订 101 室")));
        assertEquals(MessageType.USER, decoded.getMessageType());
        assertEquals("我想订 101 室", decoded.getText());
    }

    @Test
    void 助手消息往返保持类型() {
        Message decoded = codec.decode(codec.encode(new AssistantMessage("已为您生成认购单")));
        assertEquals(MessageType.ASSISTANT, decoded.getMessageType());
        assertEquals("已为您生成认购单", decoded.getText());
    }

    @Test
    void 空文本不落库() {
        assertNull(codec.encode(new AssistantMessage("")));
    }

    @Test
    void 脏数据解码返回null而不是抛异常() {
        assertNull(codec.decode("not a json"));
        assertNull(codec.decode("{\"t\":\"u\",\"c\":\"\"}"));
        assertNull(codec.decode(""));
    }
}
