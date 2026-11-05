-- Kim Bangpakong POS: Supabase central database
-- Run this entire file once in Supabase Dashboard > SQL Editor.
-- Store ID must match POS_STORE_ID in KimBangpakongPOS/index.html.
-- Never put a service_role / secret key in the POS website.

create extension if not exists pgcrypto;

create table if not exists public.pos_members (
  store_id uuid not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null default 'cashier' check (role in ('admin','cashier')),
  created_at timestamptz not null default now(),
  primary key (store_id, user_id)
);

create table if not exists public.pos_store_state (
  store_id uuid primary key,
  initialized_at timestamptz not null default now()
);

create table if not exists public.pos_products (
  store_id uuid not null,
  id bigint not null,
  name text not null,
  barcode text not null default '',
  price numeric(14,2) not null default 0 check (price >= 0),
  stock integer not null default 0 check (stock >= 0),
  category text not null default 'อื่นๆ',
  updated_at timestamptz not null default now(),
  primary key (store_id, id)
);

create table if not exists public.pos_sales (
  store_id uuid not null,
  id text not null,
  request_id uuid not null default gen_random_uuid(),
  date timestamptz not null default now(),
  items jsonb not null default '[]'::jsonb,
  customer_name text not null default '',
  subtotal numeric(14,2) not null default 0,
  discount numeric(14,2) not null default 0,
  total numeric(14,2) not null default 0,
  method text not null default 'เงินสด',
  received numeric(14,2) not null default 0,
  change numeric(14,2) not null default 0,
  created_by uuid references auth.users(id),
  primary key (store_id, id),
  unique (store_id, request_id)
);

create table if not exists public.pos_counters (
  store_id uuid not null,
  sale_date date not null,
  last_no bigint not null default 0,
  primary key (store_id, sale_date)
);

create index if not exists pos_sales_store_date_idx
  on public.pos_sales (store_id, date desc);

alter table public.pos_members enable row level security;
alter table public.pos_products enable row level security;
alter table public.pos_sales enable row level security;
alter table public.pos_store_state enable row level security;
alter table public.pos_counters enable row level security;

drop policy if exists "POS members can read own membership" on public.pos_members;
create policy "POS members can read own membership"
  on public.pos_members for select to authenticated
  using (user_id = auth.uid());

drop policy if exists "POS members can manage store products" on public.pos_products;
create policy "POS members can manage store products"
  on public.pos_products for all to authenticated
  using (
    exists (
      select 1 from public.pos_members m
      where m.store_id = pos_products.store_id
        and m.user_id = auth.uid()
    )
  )
  with check (
    exists (
      select 1 from public.pos_members m
      where m.store_id = pos_products.store_id
        and m.user_id = auth.uid()
    )
  );

drop policy if exists "POS members can read store sales" on public.pos_sales;
create policy "POS members can read store sales"
  on public.pos_sales for select to authenticated
  using (
    exists (
      select 1 from public.pos_members m
      where m.store_id = pos_sales.store_id
        and m.user_id = auth.uid()
    )
  );

grant usage on schema public to authenticated;
grant select on public.pos_members to authenticated;
grant select, insert, update, delete on public.pos_products to authenticated;
grant select on public.pos_sales to authenticated;

create or replace function public.pos_import_legacy(
  p_store_id uuid,
  p_products jsonb,
  p_sales jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_product jsonb;
  v_sale jsonb;
  v_inserted integer := 0;
  v_count integer := 0;
begin
  if auth.uid() is null or not exists (
    select 1 from public.pos_members
    where store_id = p_store_id and user_id = auth.uid()
  ) then
    raise exception 'บัญชีนี้ไม่มีสิทธิ์เข้าถึงร้านนี้';
  end if;

  insert into public.pos_store_state(store_id)
  values (p_store_id)
  on conflict (store_id) do nothing;

  get diagnostics v_inserted = row_count;
  if v_inserted = 0 then
    return jsonb_build_object('initialized', false, 'message', 'ข้อมูลส่วนกลางเริ่มต้นแล้ว');
  end if;

  for v_product in
    select value from jsonb_array_elements(coalesce(p_products, '[]'::jsonb))
  loop
    insert into public.pos_products (
      store_id, id, name, barcode, price, stock, category, updated_at
    ) values (
      p_store_id,
      (v_product->>'id')::bigint,
      coalesce(v_product->>'name', ''),
      coalesce(v_product->>'barcode', ''),
      coalesce((v_product->>'price')::numeric, 0),
      greatest(0, coalesce((v_product->>'stock')::integer, 0)),
      coalesce(nullif(v_product->>'category', ''), 'อื่นๆ'),
      now()
    )
    on conflict (store_id, id) do nothing;
    v_count := v_count + 1;
  end loop;

  for v_sale in
    select value from jsonb_array_elements(coalesce(p_sales, '[]'::jsonb))
  loop
    if coalesce(v_sale->>'id', '') <> '' then
      insert into public.pos_sales (
        store_id, id, request_id, date, items, customer_name,
        subtotal, discount, total, method, received, change, created_by
      ) values (
        p_store_id,
        v_sale->>'id',
        gen_random_uuid(),
        coalesce((v_sale->>'date')::timestamptz, now()),
        coalesce(v_sale->'items', '[]'::jsonb),
        coalesce(v_sale->>'customerName', ''),
        coalesce((v_sale->>'subtotal')::numeric, 0),
        coalesce((v_sale->>'discount')::numeric, 0),
        coalesce((v_sale->>'total')::numeric, 0),
        coalesce(v_sale->>'method', 'เงินสด'),
        coalesce((v_sale->>'received')::numeric, 0),
        coalesce((v_sale->>'change')::numeric, 0),
        auth.uid()
      )
      on conflict (store_id, id) do nothing;
    end if;
  end loop;

  return jsonb_build_object('initialized', true, 'products_imported', v_count);
end;
$$;

create or replace function public.pos_complete_sale(
  p_store_id uuid,
  p_sale jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_request_id uuid;
  v_existing public.pos_sales%rowtype;
  v_item jsonb;
  v_product public.pos_products%rowtype;
  v_qty integer;
  v_items jsonb := '[]'::jsonb;
  v_subtotal numeric(14,2) := 0;
  v_discount numeric(14,2) := 0;
  v_total numeric(14,2) := 0;
  v_method text;
  v_received numeric(14,2);
  v_change numeric(14,2);
  v_customer text;
  v_sale_date timestamptz := now();
  v_day date;
  v_no bigint;
  v_sale_id text;
begin
  if auth.uid() is null or not exists (
    select 1 from public.pos_members
    where store_id = p_store_id and user_id = auth.uid()
  ) then
    raise exception 'บัญชีนี้ไม่มีสิทธิ์เข้าถึงร้านนี้';
  end if;

  v_request_id := coalesce(nullif(p_sale->>'requestId','')::uuid, gen_random_uuid());

  select * into v_existing
  from public.pos_sales
  where store_id = p_store_id and request_id = v_request_id;

  if found then
    return jsonb_build_object(
      'id', v_existing.id, 'date', v_existing.date,
      'items', v_existing.items, 'customerName', v_existing.customer_name,
      'subtotal', v_existing.subtotal, 'discount', v_existing.discount,
      'total', v_existing.total, 'method', v_existing.method,
      'received', v_existing.received, 'change', v_existing.change
    );
  end if;

  if jsonb_typeof(p_sale->'items') <> 'array'
     or jsonb_array_length(p_sale->'items') = 0 then
    raise exception 'ไม่มีสินค้าในบิล';
  end if;

  -- Lock product rows in ID order. Two registers cannot sell the same
  -- remaining stock at the same time and drive stock below zero.
  for v_item in
    select value
    from jsonb_array_elements(p_sale->'items')
    order by (value->>'id')::bigint
  loop
    v_qty := coalesce((v_item->>'qty')::integer, 0);
    if v_qty <= 0 then
      raise exception 'จำนวนสินค้าไม่ถูกต้อง';
    end if;

    select * into v_product
    from public.pos_products
    where store_id = p_store_id and id = (v_item->>'id')::bigint
    for update;

    if not found then
      raise exception 'ไม่พบสินค้าในฐานข้อมูลกลาง (รหัส %) ', v_item->>'id';
    end if;
    if v_product.stock < v_qty then
      raise exception 'สต็อกไม่พอ: % (เหลือ %)', v_product.name, v_product.stock;
    end if;

    update public.pos_products
       set stock = stock - v_qty, updated_at = now()
     where store_id = p_store_id and id = v_product.id;

    v_subtotal := v_subtotal + (v_product.price * v_qty);
    v_items := v_items || jsonb_build_array(jsonb_build_object(
      'id', v_product.id, 'name', v_product.name,
      'price', v_product.price, 'qty', v_qty
    ));
  end loop;

  v_discount := greatest(0, least(
    v_subtotal, coalesce((p_sale->>'discount')::numeric, 0)
  ));
  v_total := v_subtotal - v_discount;
  v_method := coalesce(nullif(p_sale->>'method',''), 'เงินสด');
  v_received := coalesce((p_sale->>'received')::numeric, v_total);
  v_customer := coalesce(p_sale->>'customerName', '');

  if v_method = 'เงินสด' and v_received < v_total then
    raise exception 'ยอดเงินที่รับมาไม่ครบ หลังตรวจสอบราคาล่าสุด';
  end if;

  v_change := case when v_method = 'เงินสด'
    then greatest(0, v_received - v_total) else 0 end;

  v_day := (v_sale_date at time zone 'Asia/Bangkok')::date;
  insert into public.pos_counters(store_id, sale_date, last_no)
  values (p_store_id, v_day, 1)
  on conflict (store_id, sale_date)
  do update set last_no = public.pos_counters.last_no + 1
  returning last_no into v_no;

  v_sale_id := 'KB' || to_char(v_day, 'YYYYMMDD') || '-' || lpad(v_no::text, 4, '0');

  insert into public.pos_sales (
    store_id, id, request_id, date, items, customer_name,
    subtotal, discount, total, method, received, change, created_by
  ) values (
    p_store_id, v_sale_id, v_request_id, v_sale_date, v_items, v_customer,
    v_subtotal, v_discount, v_total, v_method, v_received, v_change, auth.uid()
  );

  return jsonb_build_object(
    'id', v_sale_id, 'date', v_sale_date, 'items', v_items,
    'customerName', v_customer, 'subtotal', v_subtotal,
    'discount', v_discount, 'total', v_total, 'method', v_method,
    'received', v_received, 'change', v_change
  );
end;
$$;

revoke all on function public.pos_import_legacy(uuid, jsonb, jsonb) from public, anon;
revoke all on function public.pos_complete_sale(uuid, jsonb) from public, anon;
grant execute on function public.pos_import_legacy(uuid, jsonb, jsonb) to authenticated;
grant execute on function public.pos_complete_sale(uuid, jsonb) to authenticated;

-- Create user accounts in Authentication > Users first, then add each account
-- to this store. Replace the example email with the actual login email:
--
-- insert into public.pos_members (store_id, user_id, role)
-- select '3e0bf7a4-2582-4b69-8c96-8e24e3bfe8dc'::uuid, id, 'admin'
-- from auth.users where email = 'owner@example.com'
-- on conflict (store_id, user_id) do update set role = excluded.role;
--
-- Repeat the statement for each register/cashier account.
