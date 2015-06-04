package com.atlassian.stash.plugin.enforcecommit;

import com.atlassian.stash.commit.CommitService;
import com.atlassian.stash.content.Changeset;
import com.atlassian.stash.content.ChangesetsBetweenRequest;
import com.atlassian.stash.hook.HookResponse;
import com.atlassian.stash.hook.repository.PreReceiveRepositoryHook;
import com.atlassian.stash.hook.repository.RepositoryHookContext;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.RefChangeType;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.setting.RepositorySettingsValidator;
import com.atlassian.stash.setting.Settings;
import com.atlassian.stash.setting.SettingsValidationErrors;
import com.atlassian.stash.util.PageRequestImpl;
import com.google.common.collect.Lists;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class EnforceCommitHook implements PreReceiveRepositoryHook, RepositorySettingsValidator {


    private static final PageRequestImpl PAGE_REQUEST = new PageRequestImpl(0, 100);

    private static final String DEFAULT_PATTERN = "((?<!([A-Z]{1,10})-?)[A-Z]+-\\d+)";

    private static final String DEFAULT_MESSAGE =
            "The git commit message(s) for the following\n" +
                    "must be prepended with a valid JIRA key.\n" +
                    "Use CAPS for the project key.\n" +
                    "For example:\n" +
                    "    KEY-123: Your message here\n" +
                    "Use \"git commit --amend -m\" to fix the message or\n" +
                    "\"git rebase\" if you are pushing multiple commits.";

    private static final String SETTINGS_PATTERN = "pattern";
    private static final String SETTINGS_MESSAGE = "message";

    private final CommitService commitService;

    public EnforceCommitHook(CommitService commitService) {
        this.commitService = commitService;
    }

    @Override
    public boolean onReceive(@Nonnull RepositoryHookContext context, @Nonnull Collection<RefChange> refChanges, @Nonnull HookResponse hookResponse) {
        Pattern pattern = Pattern.compile(getString(context.getSettings(), SETTINGS_PATTERN, DEFAULT_PATTERN));
        List<Changeset> badChangesets = Lists.newArrayList();
        for (RefChange refChange : refChanges) {
            if (refChange.getType() == RefChangeType.DELETE) {
                continue;
            }
            // TODO What about new branches?
            final ChangesetsBetweenRequest changesetsBetweenRequest = new ChangesetsBetweenRequest.Builder(context.getRepository())
                    .include(refChange.getToHash())
                    .exclude(refChange.getFromHash())
                    .build();
            for (Changeset changeset : commitService.getChangesetsBetween(changesetsBetweenRequest, PAGE_REQUEST).getValues()) {
                if (!pattern.matcher(changeset.getMessage()).find()) {
                    badChangesets.add(changeset);
                }
            }
        }
        if (badChangesets.isEmpty()) {
            return true;
        }
        hookResponse.err().println(getString(context.getSettings(), SETTINGS_MESSAGE, DEFAULT_MESSAGE));
        for (Changeset changeset : badChangesets) {
            hookResponse.err().println(String.format("Changeset '%s' with message '%s'", changeset.getId(), changeset.getMessage()));
        }
        return false;
    }

    @Override
    public void validate(@Nonnull Settings settings, @Nonnull SettingsValidationErrors errors, @Nonnull Repository repository) {
        try {
            //noinspection ResultOfMethodCallIgnored
            Pattern.compile(getString(settings, SETTINGS_PATTERN, DEFAULT_PATTERN));
        } catch (PatternSyntaxException e) {
            errors.addFieldError(SETTINGS_PATTERN, e.getMessage());
        }
    }

    private static String getString(Settings settings, String key, String def) {
        String value = settings.getString(key, "");
        return value.trim().isEmpty() ? def : value;
    }

}
