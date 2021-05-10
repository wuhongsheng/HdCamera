package com.wt.wtcamera;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 菜单
 *
 * @author whs
 * @date 2020/3/27
 */
public class MenuAdapter extends BaseQuickAdapter<String, BaseViewHolder> {
    public MenuAdapter(@Nullable List<String> data) {
        super(R.layout.list_item, data);
    }
    @Override
    protected void onItemViewHolderCreated(@NonNull BaseViewHolder viewHolder, int viewType) {
        // 绑定 view
        DataBindingUtil.bind(viewHolder.itemView);
    }

    @Override
    protected void convert(@NotNull BaseViewHolder helper, String item) {
        if (item == null) {
            return;
        }

        // 获取 Binding
        ViewDataBinding binding = helper.getBinding();
        if (binding != null) {
            binding.setVariable(BR.data,item);
            binding.executePendingBindings();
        }
    }
}
