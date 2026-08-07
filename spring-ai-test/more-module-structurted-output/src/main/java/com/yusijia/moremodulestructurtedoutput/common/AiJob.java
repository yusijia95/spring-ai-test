package com.yusijia.moremodulestructurtedoutput.common;

import java.util.Map;

public class AiJob {
    public record Job(JobType jobType, Map<String, String> params) {
    }
    public enum JobType {
        CANCEL,
        QUERY,
        OTHER
    }
}
